package io.vyrnforge.filestorage.security;

import io.vyrnforge.filestorage.domain.StorageModel.BackendId;
import io.vyrnforge.filestorage.security.SecuredFiles.*;
import io.vyrnforge.platform.kernel.context.*;
import io.vyrnforge.platform.kernel.identity.*;
import io.vyrnforge.platform.kernel.pipeline.*;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Platform-level authority, deliberately independent of tenant policy administration. */
public final class SecuredBackendAdmin {
    public enum Action { LIST, READ, REGISTER, REGISTER_LOCATION, REVISE_CREDENTIAL, VALIDATE, ACTIVATE, DRAIN, RETIRE }

    @FunctionalInterface
    public interface Bindings {
        Optional<ActorReference> resolve(String issuer, String subject, String audience);
    }

    @FunctionalInterface
    public interface Permissions {
        /** LIST and REGISTER require a global permission (backend is null); other actions require exact backend. */
        boolean permits(ActorReference actor, Action action, BackendId backend);
    }

    public record Authorized(ActorReference actor, CorrelationContext correlation) {}

    private final Trust trust;
    private final Verifier verifier;
    private final Bindings bindings;
    private final Permissions permissions;
    private final Clock clock;

    public SecuredBackendAdmin(Trust trust, Verifier verifier, Bindings bindings, Permissions permissions, Clock clock) {
        this.trust = Objects.requireNonNull(trust);
        this.verifier = Objects.requireNonNull(verifier);
        this.bindings = Objects.requireNonNull(bindings);
        this.permissions = Objects.requireNonNull(permissions);
        this.clock = Objects.requireNonNull(clock);
    }

    public Authorized authorize(String bearer, UUID correlationId, Action action, BackendId backend) {
        var verified = AccessAuthentication.verify(trust, verifier, clock, bearer);
        try {
            Objects.requireNonNull(action);
            boolean global = action == Action.LIST || action == Action.REGISTER;
            if (global != (backend == null)) throw new SecurityFailure(SecurityCode.ACCESS_DENIED);
            var actor = bindings.resolve(verified.issuer(), verified.subject(), trust.audience()).orElseThrow();
            if (actor.actorType() != ActorType.USER) throw new SecurityFailure(SecurityCode.ACCESS_DENIED);
            // Kernel explicitly establishes tenantless context; tenant membership cannot confer this privilege.
            var pipeline = new RequestContextPipeline<ValidatedIdentityCredential>(
                    new ValidatedIdentityAdapter(), (a, t) -> false, (t, m) -> null,
                    (ctx, key, resource) -> ctx.actor().equals(actor) && !ctx.tenant().isTenantScoped()
                            && action.name().equals(key) && permissions.permits(actor, action, backend), clock);
            var credential = new ValidatedIdentityCredential(actor, AuthorizationProjection.empty(),
                    new AuthenticationContext(verified.authenticatedAt(), Set.of()));
            var correlation = correlationId == null ? CorrelationContext.create() : CorrelationContext.create(correlationId);
            var resolved = pipeline.resolve(new RequestContextInput<>(credential, TenantContext.none(),
                    correlation, EndpointPolicy.tenantless(action.name()), null));
            return new Authorized(actor, resolved.correlation());
        } catch (RuntimeException ex) {
            throw new SecurityFailure(SecurityCode.ACCESS_DENIED);
        }
    }
}
