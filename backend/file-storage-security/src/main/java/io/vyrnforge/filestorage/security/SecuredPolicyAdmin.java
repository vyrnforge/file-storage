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
import static io.vyrnforge.filestorage.security.SecurityObservation.Boundary.POLICY_ADMIN;
import static io.vyrnforge.filestorage.security.SecurityObservation.Reason.*;

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
    private final SecurityObservation observations;

    public SecuredPolicyAdmin(Trust trust, Verifier verifier, Bindings bindings,
            TenantMembershipPort membership, ModuleEntitlementPort entitlement,
            Permissions permissions, Clock clock) {
        this(trust,verifier,bindings,membership,entitlement,permissions,clock,SecurityObservation.NONE);
    }

    public SecuredPolicyAdmin(Trust trust, Verifier verifier, Bindings bindings,
            TenantMembershipPort membership, ModuleEntitlementPort entitlement, Permissions permissions,
            Clock clock, SecurityObservation observations) {
        this.observations=Objects.requireNonNull(observations);
        this.trust = Objects.requireNonNull(trust);
        this.verifier = Objects.requireNonNull(verifier);
        this.bindings = Objects.requireNonNull(bindings);
        this.membership = Objects.requireNonNull(membership);
        this.entitlement = Objects.requireNonNull(entitlement);
        this.permissions = Objects.requireNonNull(permissions);
        this.clock = Objects.requireNonNull(clock);
    }

    public Authorized authorize(String bearer, Scope scope, UUID correlationId, Action action) {
        var verified = AccessAuthentication.verify(trust, verifier, clock, bearer,observations,POLICY_ADMIN);
        try {
            Objects.requireNonNull(scope);
            Objects.requireNonNull(action);
            var actor = SecurityObservation.authority(observations,POLICY_ADMIN,BINDING_UNAVAILABLE,()->Objects.requireNonNull(bindings.resolve(verified.issuer(), verified.subject(), trust.audience(), scope.tenant())))
                    .orElseThrow();
            if (actor.actorType() != ActorType.USER) throw new SecurityFailure(SecurityCode.ACCESS_DENIED);
            var pipeline = new RequestContextPipeline<ValidatedIdentityCredential>(
                    new ValidatedIdentityAdapter(), (a,t)->SecurityObservation.authority(observations,POLICY_ADMIN,MEMBERSHIP_UNAVAILABLE,()->membership.isMember(a,t)),
                    (tenant, module) -> {
                        ModuleEntitlement e = SecurityObservation.authority(observations,POLICY_ADMIN,ENTITLEMENT_UNAVAILABLE,()->entitlement.getEntitlement(tenant,module));
                        if(e==null)SecurityObservation.emit(observations,POLICY_ADMIN,ENTITLEMENT_UNAVAILABLE);
                        return e != null && e.effectiveAt() != null && clock.instant().isBefore(e.effectiveAt()) ? null : e;
                    },
                    (ctx, key, resource) -> ctx.actor().equals(actor)
                            && ctx.tenant().tenantId().equals(scope.tenant().value())
                            && action.name().equals(key) && SecurityObservation.authority(observations,POLICY_ADMIN,PERMISSION_UNAVAILABLE,()->permissions.permits(actor,scope,action)));
            var credential = new ValidatedIdentityCredential(actor, AuthorizationProjection.empty(),
                    new AuthenticationContext(verified.authenticatedAt(), Set.of()));
            var correlation = correlationId == null ? CorrelationContext.create() : CorrelationContext.create(correlationId);
            var resolved = pipeline.resolve(new RequestContextInput<>(credential,
                    TenantContext.tenant(scope.tenant().value()), correlation,
                    EndpointPolicy.tenantModule("file-storage", action.name()), null));
            return new Authorized(actor, resolved.correlation());
        } catch (RuntimeException ex) {
            SecurityObservation.emit(observations,POLICY_ADMIN,AUTHORIZATION_DENIED);
            throw new SecurityFailure(SecurityCode.ACCESS_DENIED);
        }
    }
}
