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
