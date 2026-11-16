package io.vyrnforge.filestorage.runtime;

import io.vyrnforge.filestorage.persistence.JdbcTransactions;
import io.vyrnforge.filestorage.persistence.JdbcPolicyControl;
import io.vyrnforge.filestorage.application.PolicyAuthority;
import io.vyrnforge.filestorage.persistence.StorageMigrations;
import io.vyrnforge.filestorage.security.SecuredFiles;
import io.vyrnforge.platform.kernel.ports.TenantMembershipPort;
import io.vyrnforge.platform.kernel.ports.ModuleEntitlementPort;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;

/** Explicit composition; not auto-configured. Only trusted deployment code may supply authority. */
public final class RuntimeComposition {
    private RuntimeComposition() {}

    /** Separate explicit operator action; never invoked by HTTP startup. */
    public static void migrate(DataSource source) { StorageMigrations.migrate(source); }

    /** Explicit internal adapter composition. No provider, scheduler or HTTP route activation. */
    public static io.vyrnforge.filestorage.persistence.JdbcUploadTransfers uploadTransfers(
            RuntimeDatabaseRoles roles,Clock clock,Duration ttl,int timeout){
        return new io.vyrnforge.filestorage.persistence.JdbcUploadTransfers(roles.source(RuntimeDatabaseRoles.Role.TRANSFER),clock,ttl,timeout);
    }
    public static io.vyrnforge.filestorage.persistence.JdbcDownloadTransfers downloadTransfers(
            RuntimeDatabaseRoles roles,Clock clock,Duration ttl,int timeout){
        return new io.vyrnforge.filestorage.persistence.JdbcDownloadTransfers(roles.source(RuntimeDatabaseRoles.Role.TRANSFER),clock,ttl,timeout);
    }
    public static io.vyrnforge.filestorage.persistence.JdbcCompletionWork completionWork(
            RuntimeDatabaseRoles roles,Clock clock,Duration ttl,int timeout,Duration operationLease,
            Duration retryDelay,int attempts,io.vyrnforge.platform.kernel.context.ActorReference worker){
        var source=roles.source(RuntimeDatabaseRoles.Role.WORKER);
        var transfers=new io.vyrnforge.filestorage.persistence.JdbcUploadTransfers(source,clock,ttl,timeout);
        var operations=new io.vyrnforge.filestorage.persistence.JdbcOperations(source,clock,operationLease,retryDelay,attempts);
        var scans=new io.vyrnforge.filestorage.persistence.JdbcUploadScan(transfers,operations,worker,attempts);
        return new io.vyrnforge.filestorage.persistence.JdbcCompletionWork(transfers,operations,scans,attempts);
    }
    public static io.vyrnforge.filestorage.persistence.JdbcLifecycleGovernance governance(
            RuntimeDatabaseRoles roles,Clock clock,
            io.vyrnforge.filestorage.application.LifecycleGovernance.Authority authority,
            io.vyrnforge.filestorage.application.LifecycleGovernance.DrainVerifier verifier){
        return new io.vyrnforge.filestorage.persistence.JdbcLifecycleGovernance(roles.source(RuntimeDatabaseRoles.Role.GOVERNANCE),clock,authority,verifier);
    }

    public static JdbcPolicyControl policyControl(DataSource source, Clock clock, PolicyAuthority authority) {
        return new JdbcPolicyControl(source, clock, authority);
    }

    public static SecuredFiles secured(DataSource source, SecuredFiles.Trust trust,
            SecuredFiles.Verifier verifier, SecuredFiles.Bindings bindings,
            TenantMembershipPort membership, ModuleEntitlementPort entitlement,
            SecuredFiles.Grants grants, Clock clock, Duration sessionTtl, Duration lease) {
        return new SecuredFiles(trust, verifier, bindings, membership, entitlement, grants,
                new JdbcTransactions(source, clock, lease), clock, sessionTtl);
    }
}
