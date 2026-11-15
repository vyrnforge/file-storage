package io.vyrnforge.filestorage.runtime.transfer;

import io.vyrnforge.filestorage.domain.StorageModel.*;
import java.time.Instant;
import java.util.Objects;

/** Internal only; never serialize or log a transfer instruction or its placement. */
public record TransferInstruction(TenantId tenant, String actorId, String application, Action action,
        FileId file, VersionId version, SessionId session, BackendId backend, long revision,
        Locator locator, long size, Instant expiresAt, String sha256) {
    public enum Action { UPLOAD, DOWNLOAD }

    public TransferInstruction(TenantId tenant,String actorId,String application,Action action,FileId file,
            VersionId version,SessionId session,BackendId backend,long revision,Locator locator,long size,Instant expiresAt){
        this(tenant,actorId,application,action,file,version,session,backend,revision,locator,size,expiresAt,null);
    }

    public TransferInstruction {
        Objects.requireNonNull(tenant);
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(application);
        Objects.requireNonNull(action);
        Objects.requireNonNull(file);
        Objects.requireNonNull(version);
        Objects.requireNonNull(backend);
        Objects.requireNonNull(locator);
        Objects.requireNonNull(expiresAt);
        if (actorId.isBlank() || application.isBlank() || revision < 1 || size < 0
                || (sha256 != null && (!sha256.matches("[0-9a-f]{64}") || action != Action.DOWNLOAD))
                || (action == Action.UPLOAD && (session == null || locator.providerVersion() != null)))
            throw new IllegalArgumentException("invalid transfer instruction");
    }

    @Override public String toString() { return "TransferInstruction[redacted]"; }
}
