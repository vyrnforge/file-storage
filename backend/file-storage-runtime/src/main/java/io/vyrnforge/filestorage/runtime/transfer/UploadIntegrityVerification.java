package io.vyrnforge.filestorage.runtime.transfer;

import io.vyrnforge.filestorage.domain.StorageModel.SessionId;
import io.vyrnforge.filestorage.domain.StorageModel.TenantId;
import io.vyrnforge.filestorage.persistence.JdbcUploadVerification;
import io.vyrnforge.filestorage.security.SecuredFiles;
import io.vyrnforge.filestorage.spi.StorageProvider;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/** Internal bounded integrity orchestration; reads bytes independently, never accepts a client verdict.
 * No public route, worker scheduler, scan or AVAILABLE transition in this slice.
 */
public final class UploadIntegrityVerification {
    private final SecuredFiles secured;
    private final JdbcUploadVerification repository;
    private final TransferProviderResolver resolver;
    private final TransferLimits limits;
    private final Clock clock;
    private final Semaphore slots;

    public UploadIntegrityVerification(SecuredFiles secured, JdbcUploadVerification repository,
            TransferProviderResolver resolver, TransferLimits limits, Clock clock) {
        this.secured = Objects.requireNonNull(secured);
        this.repository = Objects.requireNonNull(repository);
        this.resolver = Objects.requireNonNull(resolver);
        this.limits = Objects.requireNonNull(limits);
        this.clock = Objects.requireNonNull(clock);
        slots = new Semaphore(limits.concurrency());
    }

    /** Reuses explicit UPLOAD_TRANSFER grant for the initiating caller. Returns logical evidence only. */
    public record Result(SessionId session, boolean integrityMatched) {}

    public Result verify(String bearer, TenantId tenant, SessionId session, String expectedSha256, UUID correlationId) {
        if (!slots.tryAcquire()) throw new TransferFailure(TransferFailure.Code.BUSY);
        try {
            var deadline = new Deadline(limits.timeout());
            var authorized = secured.authorizeUpload(bearer, tenant, correlationId, session);
            var job = repository.request(authorized.value(), session, expectedSha256, authorized.correlation());
            if (job.matched()) return new Result(session, true);
            var pin = job.pin();
            if (pin.size() > limits.maxBytes()) throw new TransferFailure(TransferFailure.Code.TOO_LARGE);
            deadline.check();
            if (!clock.instant().isBefore(pin.expiresAt())) throw new TransferFailure(TransferFailure.Code.DENIED);
            // Internal verification read: not a consumer download authorization.
            var instruction = new TransferInstruction(pin.caller().tenant(), pin.caller().actor(),
                    pin.caller().consumer(), TransferInstruction.Action.UPLOAD, pin.file(), pin.version(),
                    pin.session(), pin.backend(), pin.revision(), pin.locator(), pin.size(), pin.expiresAt());
            String digest;
            try (var lease = Objects.requireNonNull(resolver.resolve(instruction))) {
                var provider = Objects.requireNonNull(lease.provider());
                var head = provider.head(pin.locator(), deadline.call());
                if (head == null || head.size() != pin.size()) throw new TransferFailure(TransferFailure.Code.INVALID);
                var hash = MessageDigest.getInstance("SHA-256");
                try (var stream = provider.get(pin.locator(), deadline.call())) {
                    byte[] buffer = new byte[limits.bufferBytes()];
                    long remaining = pin.size();
                    while (remaining > 0) {
                        deadline.check();
                        int n = stream.read(buffer, 0, (int)Math.min(remaining, buffer.length));
                        if (n <= 0) throw new TransferFailure(TransferFailure.Code.INVALID);
                        hash.update(buffer, 0, n); remaining -= n;
                    }
                    deadline.check();
                    if (stream.read() != -1) throw new TransferFailure(TransferFailure.Code.INVALID);
                }
                deadline.check();
                digest = HexFormat.of().formatHex(hash.digest());
                if (!digest.equals(job.expectedSha256()) || (head.sha256() != null && !digest.equals(head.sha256())))
                    throw new TransferFailure(TransferFailure.Code.INVALID);
            } catch (StorageProvider.StorageFailure ex) {
                throw new TransferFailure(switch (ex.code()) {
                    case NOT_FOUND -> TransferFailure.Code.NOT_FOUND;
                    case TIMEOUT -> TransferFailure.Code.TIMEOUT;
                    case INVALID -> TransferFailure.Code.INVALID;
                    case FORBIDDEN -> TransferFailure.Code.DENIED;
                    default -> TransferFailure.Code.UNAVAILABLE;
                });
            } catch (IOException | NoSuchAlgorithmException ex) {
                throw new TransferFailure(TransferFailure.Code.UNAVAILABLE);
            }
            deadline.check();
            // Fresh external authorization plus local grant/resource CAS before recording evidence.
            var current = secured.authorizeUpload(bearer, tenant, correlationId, session);
            if (!current.value().equals(authorized.value())) throw new TransferFailure(TransferFailure.Code.DENIED);
            repository.matched(job, pin.size(), digest, current.correlation());
            return new Result(session, true);
        } finally { slots.release(); }
    }

    private static final class Deadline {
        private final long start = System.nanoTime();
        private final long budget;
        Deadline(Duration timeout) { budget = timeout.toNanos(); }
        void check() {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() - start >= budget)
                throw new TransferFailure(TransferFailure.Code.TIMEOUT);
        }
        StorageProvider.Call call() {
            check();
            return new StorageProvider.Call(Duration.ofNanos(Math.max(1, budget - (System.nanoTime() - start))));
        }
    }
}
