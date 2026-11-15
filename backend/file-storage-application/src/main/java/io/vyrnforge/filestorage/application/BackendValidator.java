package io.vyrnforge.filestorage.application;

import io.vyrnforge.filestorage.domain.StorageModel.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Trusted deployment adapter, never a consumer request or a caller-supplied
 * validation assertion. Implementations must enforce approved DNS/egress,
 * TLS, no redirects, bounded deadlines/concurrency/canary cleanup, exact
 * credential resolution and actual provider identity for every location.
 * No credential material or raw provider errors in reports or logs.
 */
@FunctionalInterface
public interface BackendValidator {
    Report validate(Candidate candidate);

    enum Check {
        TLS, ENDPOINT_EGRESS, EXACT_CREDENTIAL, PROVIDER_IDENTITY,
        REQUIRED_CAPABILITIES, PRIVATE_BUCKET, ENCRYPTION,
        UNVERSIONED, LEAST_PRIVILEGE, CANARY_CLEANUP
    }

    record Candidate(BackendId backend, long revision, Target target,
                     CredentialRef credential, List<Location> locations) {
        public Candidate {
            Objects.requireNonNull(backend);
            Objects.requireNonNull(target);
            Objects.requireNonNull(credential);
            locations = List.copyOf(locations);
        }
        @Override public String toString() { return "Candidate[redacted]"; }
    }

    /** All checks must pass for every exact location, including provider identity. */
    record Report(BackendId backend, long revision, Map<LocationId, Set<Check>> checks) {
        public Report {
            Objects.requireNonNull(backend);
            checks = checks.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
        }
        @Override public String toString() { return "Report[redacted]"; }
    }
}
