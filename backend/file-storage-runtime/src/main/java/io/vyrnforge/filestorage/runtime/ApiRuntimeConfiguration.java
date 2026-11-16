package io.vyrnforge.filestorage.runtime;

import io.vyrnforge.filestorage.persistence.JdbcPolicyControl;
import io.vyrnforge.filestorage.runtime.http.ApiHttpConfiguration;
import io.vyrnforge.filestorage.runtime.http.PolicyAdminHttpConfiguration;
import io.vyrnforge.filestorage.runtime.http.BackendAdminHttpConfiguration;
import io.vyrnforge.filestorage.security.SecuredFiles;
import io.vyrnforge.filestorage.security.SecuredPolicyAdmin;
import io.vyrnforge.filestorage.security.SecuredBackendAdmin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Explicit API/COMBINED composition. Default executable never imports this configuration. */
@Configuration(proxyBeanMethods = false)
@Import({ApiHttpConfiguration.class, PolicyAdminHttpConfiguration.class, BackendAdminHttpConfiguration.class})
public class ApiRuntimeConfiguration {
    @Bean
    SecuredFiles securedFiles(ApiRuntimeDependencies d, org.springframework.core.env.Environment environment) {
        if(d.databaseRoles()!=null){
            d.databaseRoles().requireReady();
            var roles=d.databaseRoles();
            var grants=new io.vyrnforge.filestorage.persistence.JdbcGrants(roles.source(RuntimeDatabaseRoles.Role.GRANT_READ),d.clock(),256,5);
            var transactions=new io.vyrnforge.filestorage.persistence.JdbcTransactions(
                roles.source(RuntimeDatabaseRoles.Role.METADATA),roles.source(RuntimeDatabaseRoles.Role.LOGICAL),
                roles.source(RuntimeDatabaseRoles.Role.PURGE_PREPARATION),d.clock(),d.lease());
            return new SecuredFiles(d.trust(),d.verifier(),d.consumer().bindings(),d.membership(),d.entitlement(),
                grants,transactions,d.clock(),d.sessionTtl());
        }
        if(environment.getProperty("fs.require-separated-roles",Boolean.class,false))
            throw new IllegalStateException("separated database roles required");
        return RuntimeComposition.secured(d.source(), d.trust(), d.verifier(), d.consumer().bindings(),
                d.membership(), d.entitlement(), d.consumer().grants(), d.clock(), d.sessionTtl(), d.lease());
    }

    @Bean
    SecuredPolicyAdmin securedPolicyAdmin(ApiRuntimeDependencies d) {
        return new SecuredPolicyAdmin(d.trust(), d.verifier(), d.policy().bindings(),
                d.membership(), d.entitlement(), d.policy().permissions(), d.clock());
    }

    @Bean
    JdbcPolicyControl policyControl(ApiRuntimeDependencies d) {
        return RuntimeComposition.policyControl(d.source(RuntimeDatabaseRoles.Role.PLACEMENT), d.clock(), d.policy().locations());
    }

    @Bean
    SecuredBackendAdmin securedBackendAdmin(ApiRuntimeDependencies d) {
        return new SecuredBackendAdmin(d.trust(), d.verifier(), d.backend().bindings(), d.backend().permissions(), d.clock());
    }

    @Bean
    BackendAdministration backendAdministration(ApiRuntimeDependencies d) {
        return new BackendAdministration(d.source(RuntimeDatabaseRoles.Role.BACKEND), d.clock(), d.backend().approvedTargets(), d.backend().validator());
    }

    @Bean
    RuntimeReadiness runtimeReadiness(ApiRuntimeDependencies d) {
        return d.databaseRoles()==null ? new RuntimeReadiness(d.source(),d.readiness())
                : new RuntimeReadiness(d.source(RuntimeDatabaseRoles.Role.READINESS),()->d.readiness().ready()&&d.databaseRoles().ready());
    }
}
