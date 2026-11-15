package io.vyrnforge.filestorage.application;

import io.vyrnforge.filestorage.domain.StorageModel.LocationId;
import io.vyrnforge.filestorage.domain.StorageModel.Scope;
import io.vyrnforge.platform.kernel.context.ActorReference;
import java.util.Set;

/**
 * Trusted control-plane authority. Must authorize the exact actor, tenant,
 * application and purpose, plus every supplied location. An empty set asks
 * only for scope permission. Never derive authority from a request boolean.
 * Live IAM/entitlement binding and revocation policy belong to composition.
 */
@FunctionalInterface
public interface PolicyAuthority {
    boolean permits(ActorReference actor, Scope scope, Set<LocationId> locations);
}
