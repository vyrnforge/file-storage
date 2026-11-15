package io.vyrnforge.filestorage.security;

import io.vyrnforge.filestorage.domain.StorageModel.Scope;
import io.vyrnforge.filestorage.domain.StorageModel.TenantId;
import io.vyrnforge.filestorage.security.SecuredFiles.*;
import io.vyrnforge.platform.kernel.context.*;
import io.vyrnforge.platform.kernel.identity.*;
import io.vyrnforge.platform.kernel.pipeline.*;
import io.vyrnforge.platform.kernel.ports.*;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Trusted policy administration boundary; consumer grants do not confer admin actions. */
public final class SecuredPolicyAdmin {
    public enum Action { POLICY_READ, POLICY_PUBLISH, POLICY_ROLLBACK }

    /** Separate authoritative administrative subject binding. Not a token claim. */
    @FunctionalInterface
    public interface Bindings {
        Optional<ActorReference> resolve(String issuer, String subject, String audience, TenantId tenant);
    }

    /** Current exact actor/scope/action authority; no implicit tenant-admin or consumer override. */
    @FunctionalInterface
    public interface Permissions {
        boolean permits(ActorReference actor, Scope scope, Action action);
    }

    public record Authorized(ActorReference actor, CorrelationContext correlation) {}

    private final Trust trust;
    private final Verifier verifier;
    private final Bindings bindings;
    private final TenantMembershipPort membership;
    private final ModuleEntitlementPort entitlement;
    private final Permissions permissions;
    private final Clock clock;

    public SecuredPolicyAdmin(Trust trust, Verifier verifier, Bindings bindings,
            TenantMembershipPort membership, ModuleEntitlementPort entitlement,
            Permissions permissions, Clock clock) {
        this.trust = Objects.requireNonNull(trust);
        this.verifier = Objects.requireNonNull(verifier);
        this.bindings = Objects.requireNonNull(bindings);
        this.membership = Objects.requireNonNull(membership);
        this.entitlement = Objects.requireNonNull(entitlement);
        this.permissions = Objects.requireNonNull(permissions);
        this.clock = Objects.requireNonNull(clock);
    }

    public Authorized authorize(String bearer, Scope scope, UUID correlationId, Action action) {
        var verified = AccessAuthentication.verify(trust, verifier, clock, bearer);
        try {
            Objects.requireNonNull(scope);
            Objects.requireNonNull(action);
            var actor = bindings.resolve(verified.issuer(), verified.subject(), trust.audience(), scope.tenant())
                    .orElseThrow();
            if (actor.actorType() != ActorType.USER) throw new SecurityFailure(SecurityCode.ACCESS_DENIED);
            var pipeline = new RequestContextPipeline<ValidatedIdentityCredential>(
                    new ValidatedIdentityAdapter(), membership,
                    (tenant, module) -> {
                        ModuleEntitlement e = entitlement.getEntitlement(tenant, module);
                        return e != null && e.effectiveAt() != null && clock.instant().isBefore(e.effectiveAt()) ? null : e;
                    },
                    (ctx, key, resource) -> ctx.actor().equals(actor)
                            && ctx.tenant().tenantId().equals(scope.tenant().value())
                            && action.name().equals(key) && permissions.permits(actor, scope, action));
            var credential = new ValidatedIdentityCredential(actor, AuthorizationProjection.empty(),
                    new AuthenticationContext(verified.authenticatedAt(), Set.of()));
            var correlation = correlationId == null ? CorrelationContext.create() : CorrelationContext.create(correlationId);
            var resolved = pipeline.resolve(new RequestContextInput<>(credential,
                    TenantContext.tenant(scope.tenant().value()), correlation,
                    EndpointPolicy.tenantModule("file-storage", action.name()), null));
            return new Authorized(actor, resolved.correlation());
        } catch (RuntimeException ex) {
            throw new SecurityFailure(SecurityCode.ACCESS_DENIED);
        }
    }
}
