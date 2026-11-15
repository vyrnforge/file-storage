package io.vyrnforge.filestorage.application;

import io.vyrnforge.filestorage.domain.StorageModel.CredentialRef;

/**
 * Trusted deployment port, not a consumer API. Resolve the exact immutable
 * secret version or workload-identity binding named by the reference.
 * No alias-to-latest fallback, no caching beyond the approved credential lifetime.
 * The returned resource is caller-owned and must be closed. An unavailable,
 * revoked or unauthorized reference must fail, never choose another reference.
 * Implementations must not log or persist credential material.
 */
@FunctionalInterface
public interface CredentialResolver<C extends AutoCloseable> {
    C resolve(CredentialRef reference);
}
