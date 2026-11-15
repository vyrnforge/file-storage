package io.vyrnforge.filestorage.runtime.transfer;

import io.vyrnforge.filestorage.domain.StorageModel.SessionId;
import io.vyrnforge.filestorage.domain.StorageModel.TenantId;
import io.vyrnforge.filestorage.persistence.JdbcUploadScan;
import io.vyrnforge.filestorage.security.SecuredFiles;
import java.util.Objects;
import java.util.UUID;

/** Trusted internal completion command; no public route or client-supplied scan result. */
public final class UploadCompletion {
    private final SecuredFiles secured;
    private final JdbcUploadScan scans;

    public UploadCompletion(SecuredFiles secured, JdbcUploadScan scans) {
        this.secured=Objects.requireNonNull(secured);
        this.scans=Objects.requireNonNull(scans);
    }

    /** Requires existing matching integrity evidence; duplicate open request returns the same job. */
    public UUID begin(String bearer,TenantId tenant,SessionId session,UUID correlation) {
        var authorized=secured.authorizeCompletion(bearer,tenant,correlation,session);
        return scans.begin(authorized.value(),session,authorized.correlation());
    }
}
