package io.vyrnforge.filestorage.runtime;

import io.vyrnforge.filestorage.runtime.http.*;
import io.vyrnforge.filestorage.runtime.transfer.*;
import io.vyrnforge.filestorage.security.SecuredFiles;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Explicit import only. Default executable still health-only. No legacy/privileged fallback. */
@Configuration(proxyBeanMethods=false)
@EnableScheduling
@Import({ApiRuntimeConfiguration.class,UploadCommandHttpConfiguration.class,DownloadCommandHttpConfiguration.class,TransferHttpConfiguration.class})
public class StagingRuntimeConfiguration {
    @Bean StagingOperations stagingOperations(ApiRuntimeDependencies api,StagingRuntimeDependencies staging,SecuredFiles secured,
            @Value("${fs.role}") String role,@Value("${fs.staging.enabled:false}") boolean enabled,
            @Value("${fs.require-separated-roles:false}") boolean separated,
            @Value("${fs.staging.dispatch-enabled:false}") boolean dispatch,
            @Value("${fs.staging.delay-ms}") long delay){
        if(!enabled||!separated||RuntimeRole.parse(role)!=RuntimeRole.COMBINED||delay<100||delay>3600000)
            throw new IllegalStateException("invalid staging activation configuration");
        return new StagingOperations(api,staging,secured,dispatch);
    }
    @Bean CompletionCommands completionCommands(StagingOperations ops){return ops.commands();}
    @Bean DurableDownloadAuthority durableDownloadAuthority(StagingOperations ops){return ops.downloads();}
    @Bean PrivateTransferGateway privateTransferGateway(StagingOperations ops){return ops.gateway();}
}
