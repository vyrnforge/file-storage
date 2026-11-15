package io.vyrnforge.filestorage.runtime.transfer;

import io.vyrnforge.filestorage.domain.StorageModel.*;
import java.time.Instant;
import java.util.Objects;

/** Internal only; never serialize or log a transfer instruction or its placement. */
public record TransferInstruction(TenantId tenant, String actorId, String application, Action action,
        FileId file, VersionId version, SessionId session, BackendId backend, long revision,
        Locator locator, long size, Instant expiresAt) {
    public enum Action { UPLOAD, DOWNLOAD }

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
                || (action == Action.UPLOAD && (session == null || locator.providerVersion() != null)))
            throw new IllegalArgumentException("invalid transfer instruction");
    }

    @Override public String toString() { return "TransferInstruction[redacted]"; }
}
