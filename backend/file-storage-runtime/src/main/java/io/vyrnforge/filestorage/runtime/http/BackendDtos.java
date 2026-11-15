package io.vyrnforge.filestorage.runtime.http;

import io.vyrnforge.filestorage.application.FileApplication.Code;
import io.vyrnforge.filestorage.application.FileApplication.Failure;
import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.filestorage.persistence.JdbcRegistry;
import java.net.URI;
import java.util.UUID;

/** Input physical settings are write-only; all response DTOs contain logical identifiers. */
public final class BackendDtos {
    private BackendDtos() {}

    public record Register(String provider, String endpoint, String region, String addressing, String credentialRef) {
        @Override public String toString() { return "Register[redacted]"; }
    }
    public record LocationInput(String bucket, String prefix) {
        @Override public String toString() { return "LocationInput[redacted]"; }
    }
    public record CredentialInput(Long expectedRevision, String credentialRef) {
        @Override public String toString() { return "CredentialInput[redacted]"; }
    }
    public record RevisionInput(Long revision) {}
    public record ActivateInput(Long expectedRevision, Long candidateRevision) {}
    public record Registered(UUID backendId, long revision) {}
    public record Location(UUID locationId, UUID backendId) {}
    public record Backend(UUID backendId, String provider, long latestRevision, Long activeRevision) {}
    public record Revision(UUID backendId, String provider, long revision, String status, boolean validated) {}
    public record Changed(long revision) {}

    static Target target(Register input) {
        if (input == null) throw new Failure(Code.INVALID);
        try {
            return new Target(Provider.valueOf(ApiRequest.bounded(input.provider(), 16)),
                    URI.create(ApiRequest.bounded(input.endpoint(), 1024)), ApiRequest.bounded(input.region(), 64),
                    Addressing.valueOf(ApiRequest.bounded(input.addressing(), 32)));
        } catch (IllegalArgumentException ex) { throw new Failure(Code.INVALID); }
    }

    static CredentialRef reference(String value) {
        try { return new CredentialRef(ApiRequest.bounded(value, 512)); }
        catch (IllegalArgumentException ex) { throw new Failure(Code.INVALID); }
    }

    static long revision(Long value, long min) {
        if (value == null || value < min) throw new Failure(Code.INVALID);
        return value;
    }

    static Backend backend(JdbcRegistry.BackendView view) {
        return new Backend(view.backend().value(), view.provider().name(), view.latestRevision(), view.activeRevision());
    }

    static Revision revision(JdbcRegistry.RevisionView view) {
        return new Revision(view.backend().value(), view.provider().name(), view.revision(), view.status().name(), view.validated());
    }

    static Location location(JdbcRegistry.LocationView view) {
        return new Location(view.location().value(), view.backend().value());
    }
}
