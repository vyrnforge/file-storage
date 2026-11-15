package io.vyrnforge.filestorage.oss;

import io.vyrnforge.filestorage.domain.StorageModel.Location;
import io.vyrnforge.filestorage.domain.StorageModel.LocationId;
import io.vyrnforge.filestorage.domain.StorageModel.Locator;
import io.vyrnforge.filestorage.spi.StorageProvider;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import java.util.function.Consumer;

/**
 * Internal, bounded single-PUT profile for explicitly validated unversioned
 * buckets. Not a consumer endpoint or credential resolver. Owns the supplied
 * SDK client; the caller supplies a private spool directory and bounded input.
 */
public final class OssStorageProvider implements StorageProvider, AutoCloseable {
    private static final String MIME = "application/octet-stream";
    private static final int BUFFER = 64 * 1024;
    private final OSS client;
    private final Map<LocationId, Location> locations;
    private final Path spool;
    private final long maxBytes;
    private final Duration maxTimeout;
    private final Semaphore staging;
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "oss-stream-deadline");
        thread.setDaemon(true);
        return thread;
    });
    private final ThreadPoolExecutor requests;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean validated = new AtomicBoolean();
    private final int maxCursors;
    private final Duration cursorTtl;
    private final Map<String, Cursor> cursors = new LinkedHashMap<>();

    public OssStorageProvider(S3Client client, Map<LocationId, Location> locations, Path spool,
                             long maxBytes, int maxConcurrentSpools, Duration maxTimeout,
                             int maxCursors, Duration cursorTtl, int maxRequests) {
        this.client = Objects.requireNonNull(client);
        this.locations = Map.copyOf(locations);
        this.spool = Objects.requireNonNull(spool);
        this.maxTimeout = Objects.requireNonNull(maxTimeout);
        this.cursorTtl = Objects.requireNonNull(cursorTtl);
        if (this.locations.isEmpty() || maxBytes < 1 || maxBytes > 5_000_000_000L
                || maxRequests < 1 || maxConcurrentSpools < 1 || maxCursors < 1 || !Files.isDirectory(spool)
                || maxTimeout.isNegative() || maxTimeout.isZero()
                || maxTimeout.compareTo(Duration.ofHours(1)) > 0
                || cursorTtl.isNegative() || cursorTtl.isZero()
                || cursorTtl.compareTo(Duration.ofHours(1)) > 0) {
            throw failure(ErrorCode.INVALID);
        }
        // A client/credential revision belongs to exactly one logical backend.
        // A wider namespace must not accidentally list or write another
        // configured location in the same bucket.
        var backend = this.locations.values().iterator().next().backend();
        List<Location> configured = new ArrayList<>(this.locations.values());
        for (var entry : this.locations.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().id())
                    || !backend.equals(entry.getValue().backend())) throw failure(ErrorCode.INVALID);
        }
        for (int i = 0; i < configured.size(); i++) {
            Location left = configured.get(i);
            for (int j = i + 1; j < configured.size(); j++) {
                Location right = configured.get(j);
                if (left.bucket().equals(right.bucket())
                        && (namespace(left).startsWith(namespace(right))
                        || namespace(right).startsWith(namespace(left)))) {
                    throw failure(ErrorCode.INVALID);
                }
            }
        }
        this.maxBytes = maxBytes;
        this.maxCursors = maxCursors;
        staging = new Semaphore(maxConcurrentSpools);
        requests = new ThreadPoolExecutor(maxRequests, maxRequests, 0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), runnable -> {
                    Thread thread = new Thread(runnable, "oss-native-request");
                    thread.setDaemon(true);
                    return thread;
                });
        deadlines.setRemoveOnCancelPolicy(true);
    }

    /** Mandatory admission check: OSS ignores forbid-overwrite on enabled/suspended buckets. */
    public void validateUnversioned(Call call) {
        validated.set(false);
        Deadline deadline = deadline(call);
        for (String bucket : locations.values().stream().map(Location::bucket).distinct().toList()) {
            String status = invoke(deadline, () -> client.getBucketVersioning(bucket).getStatus(), value -> {});
            if (!BucketVersioningConfiguration.OFF.equals(status)) throw failure(ErrorCode.UNSUPPORTED);
        }
        validated.set(true);
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of();
    }

    @Override
    public Metadata put(Locator object, InputStream bytes, long expectedSize, Call call) {
        Objects.requireNonNull(bytes);
        Location location = target(object);
        Deadline deadline = deadline(call);
        if (expectedSize < 0 || expectedSize > maxBytes) throw failure(ErrorCode.INVALID);
        if (!staging.tryAcquire()) throw failure(ErrorCode.TRANSIENT);
        Path temporary = null;
        boolean handedOff = false;
        try {
            temporary = Files.createTempFile(spool, "upload-", ".stage");
            try (var out = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[BUFFER];
                long remaining = expectedSize;
                while (remaining > 0) {
                    deadline.check();
                    int n = bytes.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (n <= 0) throw failure(ErrorCode.INVALID);
                    out.write(buffer, 0, n);
                    remaining -= n;
                }
                deadline.check();
                if (bytes.read() != -1) throw failure(ErrorCode.INVALID);
                deadline.check();
            }
            Path staged = temporary;
            // The request thread owns the staged file until the SDK really returns,
            // even if the caller's deadline expires. No early deletion or permit release.
            CompletableFuture<Metadata> future = submit(() -> {
                try (var input = Files.newInputStream(staged)) {
                    deadline.check();
                    var metadata = new ObjectMetadata();
                    metadata.setContentLength(expectedSize);
                    metadata.setContentType(MIME);
                    metadata.setHeader("x-oss-forbid-overwrite", "true");
                    var request = new PutObjectRequest(location.bucket(), object.objectKey(), input);
                    request.setMetadata(metadata);
                    client.putObject(request);
                    return new Metadata(expectedSize, null, MIME);
                } catch (IOException ex) {
                    throw failure(ErrorCode.TRANSIENT);
                } finally {
                    cleanup(staged);
                }
            });
            handedOff = true;
            return await(future, deadline, value -> {});
        } catch (IOException ex) {
            throw failure(ErrorCode.TRANSIENT);
        } finally {
            if (!handedOff) cleanup(temporary);
        }
    }

    private void cleanup(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ex) {
                // Retain permit on failed cleanup; operator reconciliation required.
                throw failure(ErrorCode.TRANSIENT);
            }
        }
        staging.release();
    }

    @Override
    public InputStream get(Locator object, Call call) {
        Location location = target(object);
        Deadline deadline = deadline(call);
        OSSObject stream = invoke(deadline, () -> client.getObject(location.bucket(), object.objectKey()), OssStorageProvider::abort);
        try {
            return new BoundedStream(stream, deadline);
        } catch (RuntimeException ex) {
            abort(stream);
            throw ex instanceof StorageFailure ? ex : failure(ErrorCode.TRANSIENT);
        }
    }

    @Override
    public Metadata head(Locator object, Call call) {
        Location location = target(object);
        Deadline deadline = deadline(call);
        var response = invoke(deadline, () -> client.getObjectMetadata(location.bucket(), object.objectKey()), value -> {});
        String mime = response.getContentType();
        return new Metadata(response.getContentLength(), null, mime == null || mime.isBlank() ? MIME : mime);
    }

    @Override
    public void delete(Locator object, Call call) {
        Location location = target(object);
        Deadline deadline = deadline(call);
        invoke(deadline, () -> {
            try {
                client.deleteObject(location.bucket(), object.objectKey());
            } catch (OSSException ex) {
                if (!"NoSuchKey".equals(ex.getErrorCode())) throw ex;
            }
            return Boolean.TRUE;
        }, value -> {});
    }

    @Override
    public Page list(LocationId id, String prefix, String cursor, int limit, Call call) {
        Location location = location(id);
        Deadline deadline = deadline(call);
        if (prefix == null || !prefix.startsWith(namespace(location)) || limit < 1 || limit > 1000) {
            throw failure(ErrorCode.INVALID);
        }
        String continuation = cursor == null ? null : continuation(id, prefix, cursor);
        var request = new ListObjectsV2Request(location.bucket());
        request.setPrefix(prefix);
        request.setMaxKeys(limit);
        request.setContinuationToken(continuation);
        var response = invoke(deadline, () -> client.listObjectsV2(request), value -> {});
        if (response.getObjectSummaries().size() > limit) throw failure(ErrorCode.TRANSIENT);
        List<Locator> objects = new ArrayList<>();
        for (var item : response.getObjectSummaries()) {
            if (item.getKey() == null || !item.getKey().startsWith(prefix)) throw failure(ErrorCode.TRANSIENT);
            objects.add(new Locator(id, item.getKey(), null));
        }
        String next = null;
        if (response.isTruncated()) {
            String token = response.getNextContinuationToken();
            if (token == null || token.isBlank() || token.equals(continuation)) throw failure(ErrorCode.TRANSIENT);
            next = remember(id, prefix, token);
        }
        return new Page(objects, next);
    }

    // The native SDK has fixed client-level network deadlines. A bounded
    // no-queue executor also enforces each caller's remaining budget. Timed-out
    // work may still finish externally; never automatically retry a write.
    private <T> CompletableFuture<T> submit(Supplier<T> work) {
        try {
            return CompletableFuture.supplyAsync(work, requests);
        } catch (RejectedExecutionException ex) {
            throw failure(ErrorCode.TRANSIENT);
        }
    }

    private <T> T invoke(Deadline deadline, Supplier<T> work, Consumer<T> late) {
        deadline.check();
        return await(submit(work), deadline, late);
    }

    private <T> T await(CompletableFuture<T> future, Deadline deadline, Consumer<T> late) {
        try {
            return future.get(deadline.remaining(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            future.thenAccept(late);
            throw failure(ErrorCode.TIMEOUT);
        } catch (InterruptedException ex) {
            future.thenAccept(late);
            Thread.currentThread().interrupt();
            throw failure(ErrorCode.TIMEOUT);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof StorageFailure failure) throw failure;
            if (cause instanceof RuntimeException runtime) throw translate(runtime);
            throw failure(ErrorCode.TRANSIENT);
        } catch (StorageFailure ex) {
            future.thenAccept(late);
            throw ex;
        }
    }

    private record Cursor(LocationId location, String prefix, String token, long expires) {}

    private synchronized String continuation(LocationId location, String prefix, String opaque) {
        Cursor found = cursors.get(opaque);
        if (found == null || found.expires() - System.nanoTime() <= 0
                || !found.location().equals(location) || !found.prefix().equals(prefix)) {
            throw failure(ErrorCode.INVALID);
        }
        return found.token();
    }

    private synchronized String remember(LocationId location, String prefix, String token) {
        cursors.entrySet().removeIf(entry -> entry.getValue().expires() - System.nanoTime() <= 0);
        while (cursors.size() >= maxCursors) cursors.remove(cursors.keySet().iterator().next());
        String opaque = UUID.randomUUID().toString();
        cursors.put(opaque, new Cursor(location, prefix, token, System.nanoTime() + cursorTtl.toNanos()));
        return opaque;
    }

    private Location location(LocationId id) {
        if (!validated.get()) throw failure(ErrorCode.UNSUPPORTED);
        Location result = locations.get(id);
        if (result == null) throw failure(ErrorCode.INVALID);
        return result;
    }

    private Location target(Locator object) {
        Objects.requireNonNull(object);
        Location location = location(object.location());
        if (object.providerVersion() != null) throw failure(ErrorCode.UNSUPPORTED);
        if (!object.objectKey().startsWith(namespace(location))) throw failure(ErrorCode.INVALID);
        return location;
    }

    private static String namespace(Location location) {
        String prefix = location.prefix();
        return prefix.isEmpty() || prefix.endsWith("/") ? prefix : prefix + "/";
    }

    private Deadline deadline(Call call) {
        Objects.requireNonNull(call);
        if (closed.get()) throw failure(ErrorCode.TRANSIENT);
        if (call.timeout().compareTo(maxTimeout) > 0) throw failure(ErrorCode.INVALID);
        return new Deadline(System.nanoTime() + call.timeout().toNanos());
    }

    private record Deadline(long end) {
        long remaining() {
            long result = end - System.nanoTime();
            if (result <= 0 || Thread.currentThread().isInterrupted()) throw failure(ErrorCode.TIMEOUT);
            return result;
        }
        void check() { remaining(); }

    }

    private final class BoundedStream extends InputStream {
        private final OSSObject delegate;
        private final Deadline deadline;
        private final AtomicBoolean finished = new AtomicBoolean();
        private final ScheduledFuture<?> timer;
        private volatile boolean expired;

        BoundedStream(OSSObject delegate, Deadline deadline) {
            this.delegate = delegate;
            this.deadline = deadline;
            timer = deadlines.schedule(() -> {
                expired = true;
                if (finished.compareAndSet(false, true)) abort(delegate);
            }, deadline.remaining(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int read() {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) return 0;
            if (expired) throw failure(ErrorCode.TIMEOUT);
            if (finished.get()) return -1;
            try {
                deadline.check();
                int n = delegate.getObjectContent().read(buffer, offset, length);
                deadline.check();
                if (n < 0 && finished.compareAndSet(false, true)) {
                    timer.cancel(false);
                    delegate.close();
                }
                return n;
            } catch (IOException | RuntimeException ex) {
                close();
                if (expired || ex instanceof StorageFailure && ((StorageFailure) ex).code() == ErrorCode.TIMEOUT
                        || ex instanceof java.net.SocketTimeoutException) throw failure(ErrorCode.TIMEOUT);
                throw failure(ErrorCode.TRANSIENT);
            }
        }

        @Override
        public void close() {
            timer.cancel(false);
            if (finished.compareAndSet(false, true)) abort(delegate);
        }
    }

    private static void abort(OSSObject stream) {
        try { stream.forcedClose(); } catch (IOException | RuntimeException ignored) { /* neutral close */ }
    }

    private static StorageFailure translate(RuntimeException ex) {
        if (ex instanceof OSSException oss) {
            String code = oss.getErrorCode();
            if (code == null) return failure(ErrorCode.TRANSIENT);
            return failure(switch (code) {
                case "NoSuchKey", "NoSuchBucket" -> ErrorCode.NOT_FOUND;
                case "FileAlreadyExists", "FileImmutable", "ObjectAlreadyExists", "PreconditionFailed" -> ErrorCode.CONFLICT;
                case "AccessDenied", "InvalidAccessKeyId", "SignatureDoesNotMatch", "SecurityTokenExpired", "InvalidSecurityToken" -> ErrorCode.FORBIDDEN;
                case "RequestTimeout" -> ErrorCode.TIMEOUT;
                case "InvalidArgument", "InvalidRequest", "InvalidObjectName", "InvalidBucketName", "InvalidDigest", "BadDigest" -> ErrorCode.INVALID;
                default -> ErrorCode.TRANSIENT;
            });
        }
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.SocketTimeoutException || cause instanceof TimeoutException
                    || cause.getClass().getSimpleName().contains("Timeout")) return failure(ErrorCode.TIMEOUT);
            if (cause.getCause() == cause) break;
        }
        return failure(ErrorCode.TRANSIENT);
    }

    private static StorageFailure failure(ErrorCode code) {
        return new StorageFailure(code);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            deadlines.shutdownNow();
            requests.shutdown();
            try { client.shutdown(); } catch (RuntimeException ex) { throw failure(ErrorCode.TRANSIENT); }
            finally { synchronized (this) { cursors.clear(); } }
        }
    }
}
