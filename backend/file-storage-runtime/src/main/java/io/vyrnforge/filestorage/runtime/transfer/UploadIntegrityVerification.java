package io.vyrnforge.filestorage.runtime.transfer;

import io.vyrnforge.filestorage.domain.StorageModel.SessionId;
import io.vyrnforge.filestorage.domain.StorageModel.TenantId;
import io.vyrnforge.filestorage.persistence.JdbcUploadVerification;
import io.vyrnforge.filestorage.security.SecuredFiles;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/** Internal bounded integrity orchestration; reads bytes independently, never accepts a client verdict.
 * No public route, worker scheduler, scan or AVAILABLE transition in this slice.
 */
public final class UploadIntegrityVerification {
    private final SecuredFiles secured;
    private final JdbcUploadVerification repository;
    private final IntegrityReader reader;
    private final TransferLimits limits;
    private final Semaphore slots;

    public UploadIntegrityVerification(SecuredFiles secured, JdbcUploadVerification repository,
            TransferProviderResolver resolver, TransferLimits limits, Clock clock) {
        this.secured = Objects.requireNonNull(secured);
        this.repository = Objects.requireNonNull(repository);
        this.reader = new IntegrityReader(resolver,limits,clock);
        this.limits = Objects.requireNonNull(limits);
        slots = new Semaphore(limits.concurrency());
    }

    /** Reuses explicit UPLOAD_TRANSFER grant for the initiating caller. Returns logical evidence only. */
    public record Result(SessionId session, boolean integrityMatched) {}

    public Result verify(String bearer, TenantId tenant, SessionId session, String expectedSha256, UUID correlationId) {
        if (!slots.tryAcquire()) throw new TransferFailure(TransferFailure.Code.BUSY);
        try {
            var authorized = secured.authorizeUpload(bearer, tenant, correlationId, session);
            var job = repository.request(authorized.value(), session, expectedSha256, authorized.correlation());
            if (job.matched()) return new Result(session, true);
            var pin = job.pin();
            String digest = reader.measure(job,limits.timeout());
            // Fresh external authorization plus local grant/resource CAS before recording evidence.
            var current = secured.authorizeUpload(bearer, tenant, correlationId, session);
            if (!current.value().equals(authorized.value())) throw new TransferFailure(TransferFailure.Code.DENIED);
            repository.matched(job, pin.size(), digest, current.correlation());
            return new Result(session, true);
        } finally { slots.release(); }
    }

}
