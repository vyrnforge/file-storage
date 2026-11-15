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
    SecuredFiles securedFiles(ApiRuntimeDependencies d) {
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
        return RuntimeComposition.policyControl(d.source(), d.clock(), d.policy().locations());
    }

    @Bean
    SecuredBackendAdmin securedBackendAdmin(ApiRuntimeDependencies d) {
        return new SecuredBackendAdmin(d.trust(), d.verifier(), d.backend().bindings(), d.backend().permissions(), d.clock());
    }

    @Bean
    BackendAdministration backendAdministration(ApiRuntimeDependencies d) {
        return new BackendAdministration(d.source(), d.clock(), d.backend().approvedTargets(), d.backend().validator());
    }

    @Bean
    RuntimeReadiness runtimeReadiness(ApiRuntimeDependencies d) {
        return new RuntimeReadiness(d.source(), d.readiness());
    }
}
