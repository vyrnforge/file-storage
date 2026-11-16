package io.vyrnforge.filestorage.runtime;

import io.vyrnforge.filestorage.application.BackendValidator;
import io.vyrnforge.filestorage.application.PolicyAuthority;
import io.vyrnforge.filestorage.domain.StorageModel.Target;
import io.vyrnforge.filestorage.security.SecuredFiles;
import io.vyrnforge.filestorage.security.SecuredPolicyAdmin;
import io.vyrnforge.filestorage.security.SecuredBackendAdmin;
import io.vyrnforge.platform.kernel.ports.TenantMembershipPort;
import io.vyrnforge.platform.kernel.ports.ModuleEntitlementPort;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;

/** Trusted code supplies all dependencies explicitly. No environment-derived permissive defaults. */
public record ApiRuntimeDependencies(
        DataSource source, Clock clock, Duration sessionTtl, Duration lease,
        SecuredFiles.Trust trust, SecuredFiles.Verifier verifier,
        TenantMembershipPort membership, ModuleEntitlementPort entitlement,
        ConsumerAuthority consumer, PolicyAdministration policy, BackendAuthority backend,
        RuntimeReadiness.AuthorityProbe readiness, RuntimeDatabaseRoles databaseRoles) {
    /** Legacy isolated single-source composition, never staging admission. */
    public ApiRuntimeDependencies(DataSource source, Clock clock, Duration sessionTtl, Duration lease,
            SecuredFiles.Trust trust, SecuredFiles.Verifier verifier, TenantMembershipPort membership,
            ModuleEntitlementPort entitlement, ConsumerAuthority consumer, PolicyAdministration policy,
            BackendAuthority backend, RuntimeReadiness.AuthorityProbe readiness) {
        this(source,clock,sessionTtl,lease,trust,verifier,membership,entitlement,consumer,policy,backend,readiness,null);
    }
    public DataSource source(RuntimeDatabaseRoles.Role role){
        return databaseRoles==null?source:databaseRoles.source(role);
    }
    public ApiRuntimeDependencies {
        Objects.requireNonNull(source);
        Objects.requireNonNull(clock);
        Objects.requireNonNull(sessionTtl);
        Objects.requireNonNull(lease);
        Objects.requireNonNull(trust);
        Objects.requireNonNull(verifier);
        Objects.requireNonNull(membership);
        Objects.requireNonNull(entitlement);
        Objects.requireNonNull(consumer);
        Objects.requireNonNull(policy);
        Objects.requireNonNull(backend);
        Objects.requireNonNull(readiness);
        if (sessionTtl.isNegative() || sessionTtl.isZero() || lease.isNegative() || lease.isZero())
            throw new IllegalArgumentException("invalid runtime timing");
    }

    public record ConsumerAuthority(SecuredFiles.Bindings bindings, SecuredFiles.Grants grants) {
        public ConsumerAuthority { Objects.requireNonNull(bindings); Objects.requireNonNull(grants); }
        @Override public String toString() { return "ConsumerAuthority[redacted]"; }
    }

    public record PolicyAdministration(SecuredPolicyAdmin.Bindings bindings,
            SecuredPolicyAdmin.Permissions permissions, PolicyAuthority locations) {
        public PolicyAdministration {
            Objects.requireNonNull(bindings); Objects.requireNonNull(permissions); Objects.requireNonNull(locations);
        }
        @Override public String toString() { return "PolicyAdministration[redacted]"; }
    }

    public record BackendAuthority(SecuredBackendAdmin.Bindings bindings,
            SecuredBackendAdmin.Permissions permissions, Set<Target> approvedTargets, BackendValidator validator) {
        public BackendAuthority {
            Objects.requireNonNull(bindings); Objects.requireNonNull(permissions);
            approvedTargets = Set.copyOf(approvedTargets); Objects.requireNonNull(validator);
        }
        @Override public String toString() { return "BackendAuthority[redacted]"; }
    }

    @Override public String toString() { return "ApiRuntimeDependencies[redacted]"; }
}
