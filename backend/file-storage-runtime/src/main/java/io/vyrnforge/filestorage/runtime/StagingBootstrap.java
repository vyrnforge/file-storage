package io.vyrnforge.filestorage.runtime;

import io.vyrnforge.filestorage.application.BackendValidator;
import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.filestorage.oss.OssStagingFactory;
import io.vyrnforge.filestorage.s3.S3StagingFactory;
import io.vyrnforge.filestorage.security.*;
import io.vyrnforge.filestorage.spi.StorageProvider;
import io.vyrnforge.filestorage.runtime.transfer.*;
import io.vyrnforge.platform.kernel.context.ActorReference;
import io.vyrnforge.platform.kernel.persistence.jdbc.EventTransport;
import io.vyrnforge.platform.kernel.ports.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.util.*;
import org.springframework.context.ConfigurableApplicationContext;

/** Trusted deployment composition, not a plugin loader or auto-deployer.
 * Authority, scanner and event transport implementations must be provided by reviewed environment code.
 */
public final class StagingBootstrap {
    private StagingBootstrap(){}
    public record Integrations(TenantMembershipPort membership,ModuleEntitlementPort entitlement,
            SecuredFiles.Bindings consumer,ApiRuntimeDependencies.PolicyAdministration policy,
            SecuredBackendAdmin.Bindings backendBindings,SecuredBackendAdmin.Permissions backendPermissions,
            BackendValidator validator,ContentScanner scanner,EventTransport transport,ActorReference worker,
            RuntimeReadiness.AuthorityProbe ready){
        public Integrations{
            Objects.requireNonNull(membership);Objects.requireNonNull(entitlement);Objects.requireNonNull(consumer);
            Objects.requireNonNull(policy);Objects.requireNonNull(backendBindings);Objects.requireNonNull(backendPermissions);
            Objects.requireNonNull(validator);Objects.requireNonNull(scanner);Objects.requireNonNull(transport);
            Objects.requireNonNull(worker);Objects.requireNonNull(ready);
        }
        @Override public String toString(){return "Integrations[redacted]";}
    }
    public static final class Prepared{
        private final StagingBootstrapConfig config;
        private final ApiRuntimeDependencies api;
        private final StagingRuntimeDependencies staging;
        Prepared(StagingBootstrapConfig config,ApiRuntimeDependencies api,StagingRuntimeDependencies staging){
            this.config=config;this.api=api;this.staging=staging;
        }
        public boolean ready(){try{return api.readiness().ready()&&staging.readiness().ready()&&api.databaseRoles().ready();}
            catch(RuntimeException ex){return false;}}
        /** No arbitrary command-line override of the reviewed config. */
        public ConfigurableApplicationContext start(){
            if(!config.enabled||!ready())throw new IllegalStateException("staging activation not admitted");
            var app=RuntimeApplication.application(StagingRuntimeConfiguration.class);
            app.addInitializers(ctx->{
                ctx.getBeanFactory().registerSingleton("stagingApiDependencies",api);
                ctx.getBeanFactory().registerSingleton("stagingRuntimeDependencies",staging);
            });
            return app.run("--server.address=127.0.0.1","--server.port="+config.serverPort,"--fs.role=COMBINED",
                "--fs.require-separated-roles=true","--fs.staging.enabled=true","--fs.staging.delay-ms="+config.delayMillis,
                "--fs.staging.dispatch-enabled="+config.dispatch,"--spring.flyway.enabled=false",
                "--server.error.include-message=never","--server.error.include-stacktrace=never");
        }
        @Override public String toString(){return "Prepared[redacted]";}
    }
    public static Prepared prepare(Path configuration,Integrations integrations,Clock clock){
        Objects.requireNonNull(integrations);Objects.requireNonNull(clock);
        try{
            trustedFile(configuration);
            var c=StagingBootstrapConfig.load(configuration);
            var secrets=new MountedStagingSecrets(c.secretRoot);
            trustedFile(c.ca);trustedFile(c.jwks);
            if(!c.spool.equals(c.spool.toRealPath())||!Files.isDirectory(c.spool,LinkOption.NOFOLLOW_LINKS)
                ||Files.getPosixFilePermissions(c.spool).stream().anyMatch(p->p.name().startsWith("GROUP_")||p.name().startsWith("OTHERS_")))
                throw StagingBootstrapConfig.invalid();
            var bindings=new EnumMap<RuntimeDatabaseRoles.Role,RuntimeDatabaseRoles.Binding>(RuntimeDatabaseRoles.Role.class);
            for(var entry:c.roles.entrySet()){
                var role=entry.getValue();secrets.databasePassword(role.reference());
                bindings.put(entry.getKey(),new RuntimeDatabaseRoles.Binding(role.login(),new StagingRoleDataSource(c,role,secrets)));
            }
            for(var pin:c.locations.values())try(var ignored=secrets.provider(pin.credential().value())){/* validate only */}
            var roles=new RuntimeDatabaseRoles(bindings);
            var verifier=WyrmgateJwtVerifier.rs256(c.trust,()->{
                try{
                    trustedFile(c.jwks);
                    var before=Files.readAttributes(c.jwks,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                    var jwks=new String(StagingBootstrapConfig.read(c.jwks,65536),StandardCharsets.UTF_8);
                    var after=Files.readAttributes(c.jwks,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                    if(!before.lastModifiedTime().equals(after.lastModifiedTime())||before.size()!=after.size()
                        ||!Objects.equals(before.fileKey(),after.fileKey()))throw StagingBootstrapConfig.invalid();
                    return new WyrmgateJwtVerifier.KeySnapshot(c.trust.issuer(),jwks,after.lastModifiedTime().toInstant());
                }catch(Exception ex){throw new IllegalStateException("staging key snapshot unavailable");}
            },clock,c.tokenLifetime,c.keyAge);
            RuntimeReadiness.AuthorityProbe probe=()->verifier.ready()&&integrations.ready().ready();
            var targets=c.locations.values().stream().map(StagingRuntimeDependencies.Pin::target).collect(java.util.stream.Collectors.toUnmodifiableSet());
            var api=new ApiRuntimeDependencies(roles.source(RuntimeDatabaseRoles.Role.METADATA),clock,c.sessionTtl,c.idempotencyLease,
                c.trust,verifier,integrations.membership(),integrations.entitlement(),
                new ApiRuntimeDependencies.ConsumerAuthority(integrations.consumer(),caller->List.of()),integrations.policy(),
                new ApiRuntimeDependencies.BackendAuthority(integrations.backendBindings(),integrations.backendPermissions(),targets,integrations.validator()),
                probe,roles);
            Map<Provider,RegistryTransferProviderResolver.AdapterFactory<AutoCloseable>> factories=Map.of(
                Provider.S3,(target,location,revision,credential)->{
                    var secret=(MountedStagingSecrets.ProviderSecret)credential;
                    var provider=S3StagingFactory.open(target,location,secret.access(),secret.secret(),secret.token(),c.spool,c.limits.maxBytes(),c.limits.timeout());
                    return lease(provider,provider::close);
                },
                Provider.OSS,(target,location,revision,credential)->{
                    var secret=(MountedStagingSecrets.ProviderSecret)credential;
                    var provider=OssStagingFactory.open(target,location,secret.access(),secret.secret(),secret.token(),c.spool,c.limits.maxBytes(),c.limits.timeout());
                    return lease(provider,provider::close);
                });
            var staging=new StagingRuntimeDependencies("staging",c.trust,c.locations,ref->secrets.provider(ref.value()),factories,
                integrations.scanner(),integrations.transport(),integrations.worker(),probe,c.limits,c.handleTtl,
                c.operationLease,c.retryDelay,c.attempts,c.outboxBatch);
            return new Prepared(c,api,staging);
        }catch(Exception ex){throw new IllegalStateException("staging bootstrap rejected");}
    }
    private static void trustedFile(Path path)throws java.io.IOException{
        if(!path.equals(path.toRealPath())||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)
            ||Files.getPosixFilePermissions(path).stream().anyMatch(p->p==PosixFilePermission.GROUP_WRITE||p==PosixFilePermission.OTHERS_WRITE))
            throw new java.io.IOException("invalid trusted file");
    }
    private static TransferProviderResolver.Lease lease(StorageProvider provider,Runnable close){
        return new TransferProviderResolver.Lease(){
            @Override public StorageProvider provider(){return provider;}
            @Override public void close(){close.run();}
        };
    }
}
