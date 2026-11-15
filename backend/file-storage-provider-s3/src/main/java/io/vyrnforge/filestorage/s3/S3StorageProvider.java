package io.vyrnforge.filestorage.s3;

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
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/**
 * Internal, bounded single-PUT profile for explicitly validated unversioned
 * buckets. Not a consumer endpoint or credential resolver. Owns the supplied
 * SDK client; the caller supplies a private spool directory and bounded input.
 */
public final class S3StorageProvider implements StorageProvider, AutoCloseable {
    private static final String MIME = "application/octet-stream";
    private static final int BUFFER = 64 * 1024;
    private final S3Client client;
    private final Map<LocationId, Location> locations;
    private final Path spool;
    private final long maxBytes;
    private final Duration maxTimeout;
    private final Semaphore staging;
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "s3-stream-deadline");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean validated = new AtomicBoolean();
    private final int maxCursors;
    private final Duration cursorTtl;
    private final Map<String, Cursor> cursors = new LinkedHashMap<>();

    public S3StorageProvider(S3Client client, Map<LocationId, Location> locations, Path spool,
                             long maxBytes, int maxConcurrentSpools, Duration maxTimeout,
                             int maxCursors, Duration cursorTtl) {
        this.client = Objects.requireNonNull(client);
        this.locations = Map.copyOf(locations);
        this.spool = Objects.requireNonNull(spool);
        this.maxTimeout = Objects.requireNonNull(maxTimeout);
        this.cursorTtl = Objects.requireNonNull(cursorTtl);
        if (this.locations.isEmpty() || maxBytes < 1 || maxBytes > 5_000_000_000L
                || maxConcurrentSpools < 1 || maxCursors < 1 || !Files.isDirectory(spool)
                || maxTimeout.isNegative() || maxTimeout.isZero()
                || maxTimeout.compareTo(Duration.ofHours(1)) > 0
                || cursorTtl.isNegative() || cursorTtl.isZero()
                || cursorTtl.compareTo(Duration.ofHours(1)) > 0) {
            throw failure(ErrorCode.INVALID);
        }
        for (var entry : this.locations.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().id())) throw failure(ErrorCode.INVALID);
        }
        this.maxBytes = maxBytes;
        this.maxCursors = maxCursors;
        staging = new Semaphore(maxConcurrentSpools);
        deadlines.setRemoveOnCancelPolicy(true);
    }

    /** Required before admitting a profile. No silent delete-marker semantics. */
    public void validateUnversioned(Call call) {
        validated.set(false);
        Deadline deadline = deadline(call);
        try {
            for (String bucket : locations.values().stream().map(Location::bucket).distinct().toList()) {
                var response = client.getBucketVersioning(GetBucketVersioningRequest.builder()
                        .bucket(bucket).overrideConfiguration(deadline.override()).build());
                if (response.statusAsString() != null && !response.statusAsString().isBlank()) {
                    throw failure(ErrorCode.UNSUPPORTED);
                }
            }
            validated.set(true);
        } catch (SdkClientException | S3Exception ex) {
            throw translate(ex);
        }
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
        try {
            // Never start a provider write before exact length is known. Only a
            // bounded buffer is allocated; caller retains ownership of input.
            temporary = Files.createTempFile(spool, "upload-", ".stage");
            try (var out = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[BUFFER];
                long remaining = expectedSize;
                while (remaining > 0) {
                    deadline.check();
                    int n = bytes.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (n < 0) throw failure(ErrorCode.INVALID);
                    if (n == 0) throw failure(ErrorCode.INVALID);
                    out.write(buffer, 0, n);
                    remaining -= n;
                }
                deadline.check();
                if (bytes.read() != -1) throw failure(ErrorCode.INVALID);
                deadline.check();
            }
            client.putObject(PutObjectRequest.builder().bucket(location.bucket()).key(object.objectKey())
                    .ifNoneMatch("*").contentType(MIME).contentLength(expectedSize)
                    .overrideConfiguration(deadline.override()).build(), RequestBody.fromFile(temporary));
            // SHA-256 of actual persisted bytes is not inferred from an ETag.
            return new Metadata(expectedSize, null, MIME);
        } catch (SdkClientException | S3Exception ex) {
            throw translate(ex);
        } catch (IOException ex) {
            throw failure(ErrorCode.TRANSIENT);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ex) {
                    // Fail closed: retain this staging permit if private cleanup
                    // failed; operator recovery/restart must reconcile the spool.
                    throw failure(ErrorCode.TRANSIENT);
                }
            }
            staging.release();
        }
    }

    @Override
    public InputStream get(Locator object, Call call) {
        Location location = target(object);
        Deadline deadline = deadline(call);
        ResponseInputStream<GetObjectResponse> stream = null;
        try {
            stream = client.getObject(GetObjectRequest.builder().bucket(location.bucket())
                    .key(object.objectKey()).overrideConfiguration(deadline.override()).build());
            return new BoundedStream(stream, deadline);
        } catch (SdkClientException | S3Exception ex) {
            if (stream != null) abort(stream);
            throw translate(ex);
        } catch (RuntimeException ex) {
            if (stream != null) abort(stream);
            throw ex instanceof StorageFailure ? ex : failure(ErrorCode.TRANSIENT);
        }
    }

    @Override
    public Metadata head(Locator object, Call call) {
        Location location = target(object);
        Deadline deadline = deadline(call);
        try {
            var response = client.headObject(HeadObjectRequest.builder().bucket(location.bucket())
                    .key(object.objectKey()).overrideConfiguration(deadline.override()).build());
            String mime = response.contentType();
            return new Metadata(response.contentLength(), null, mime == null || mime.isBlank() ? MIME : mime);
        } catch (SdkClientException | S3Exception ex) {
            throw translate(ex);
        }
    }

    @Override
    public void delete(Locator object, Call call) {
        Location location = target(object);
        Deadline deadline = deadline(call);
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(location.bucket()).key(object.objectKey())
                    .overrideConfiguration(deadline.override()).build());
        } catch (S3Exception ex) {
            if (ex.statusCode() != 404 || !"NoSuchKey".equals(errorCode(ex))) throw translate(ex);
        } catch (SdkClientException ex) {
            throw translate(ex);
        }
    }

    @Override
    public Page list(LocationId id, String prefix, String cursor, int limit, Call call) {
        Location location = location(id);
        Deadline deadline = deadline(call);
        if (prefix == null || !prefix.startsWith(namespace(location)) || limit < 1 || limit > 1000) {
            throw failure(ErrorCode.INVALID);
        }
        String continuation = cursor == null ? null : continuation(id, prefix, cursor);
        try {
            var response = client.listObjectsV2(ListObjectsV2Request.builder().bucket(location.bucket())
                    .prefix(prefix).maxKeys(limit).continuationToken(continuation)
                    .overrideConfiguration(deadline.override()).build());
            if (response.contents().size() > limit) throw failure(ErrorCode.TRANSIENT);
            List<Locator> objects = new ArrayList<>();
            for (var item : response.contents()) {
                if (item.key() == null || !item.key().startsWith(prefix)) throw failure(ErrorCode.TRANSIENT);
                objects.add(new Locator(id, item.key(), null));
            }
            String next = null;
            if (Boolean.TRUE.equals(response.isTruncated())) {
                String token = response.nextContinuationToken();
                if (token == null || token.isBlank() || token.equals(continuation)) throw failure(ErrorCode.TRANSIENT);
                next = remember(id, prefix, token);
            }
            return new Page(objects, next);
        } catch (SdkClientException | S3Exception ex) {
            throw translate(ex);
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
        AwsRequestOverrideConfiguration override() {
            Duration timeout = Duration.ofNanos(remaining());
            return AwsRequestOverrideConfiguration.builder().apiCallTimeout(timeout)
                    .apiCallAttemptTimeout(timeout).build();
        }
    }

    private final class BoundedStream extends InputStream {
        private final ResponseInputStream<GetObjectResponse> delegate;
        private final Deadline deadline;
        private final AtomicBoolean finished = new AtomicBoolean();
        private final ScheduledFuture<?> timer;
        private volatile boolean expired;

        BoundedStream(ResponseInputStream<GetObjectResponse> delegate, Deadline deadline) {
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
                int n = delegate.read(buffer, offset, length);
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

    private static void abort(ResponseInputStream<?> stream) {
        try { stream.abort(); } catch (RuntimeException ignored) { /* neutral close */ }
    }

    private static String errorCode(S3Exception ex) {
        return ex.awsErrorDetails() == null ? "" : ex.awsErrorDetails().errorCode();
    }

    private static StorageFailure translate(RuntimeException ex) {
        if (ex instanceof ApiCallTimeoutException || ex instanceof ApiCallAttemptTimeoutException
                || ex.getCause() instanceof java.net.SocketTimeoutException) return failure(ErrorCode.TIMEOUT);
        if (ex instanceof S3Exception s3) {
            int status = s3.statusCode();
            if (status == 404) return failure(ErrorCode.NOT_FOUND);
            if (status == 409 || status == 412) return failure(ErrorCode.CONFLICT);
            if (status == 401 || status == 403) return failure(ErrorCode.FORBIDDEN);
            if (status == 408) return failure(ErrorCode.TIMEOUT);
            if (status == 400) return failure(ErrorCode.INVALID);
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
            try { client.close(); } catch (RuntimeException ex) { throw failure(ErrorCode.TRANSIENT); }
            finally { synchronized (this) { cursors.clear(); } }
        }
    }
}
