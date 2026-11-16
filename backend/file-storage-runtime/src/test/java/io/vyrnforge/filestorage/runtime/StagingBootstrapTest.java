package io.vyrnforge.filestorage.runtime;

import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.filestorage.security.*;
import io.vyrnforge.filestorage.runtime.transfer.*;
import io.vyrnforge.platform.kernel.context.*;
import io.vyrnforge.platform.kernel.ports.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class StagingBootstrapTest {
    @TempDir Path temp;
    private Path config,root;
    private String content;
    private void writePrivate(Path path,String value)throws Exception{
        Files.writeString(path,value);Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));
    }
    @BeforeEach void setup()throws Exception{
        root=Files.createDirectory(temp.resolve("secrets"),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        var spool=Files.createDirectory(temp.resolve("spool"),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        var ca=temp.resolve("ca");writePrivate(ca,"fixture-only-ca");
        var jwks=temp.resolve("jwks");writePrivate(jwks,"{\"keys\":[]}");
        config=temp.resolve("config.properties");
        content="""
            environment=staging
            staging.enabled=false
            dispatch.enabled=false
            server.port=8080
            db.host=db.staging.example.invalid
            db.port=5432
            db.name=fs_staging
            db.ca=/etc/file-storage/staging/db-ca.pem
            db.connect-seconds=3
            db.socket-seconds=10
            secret.root=/run/file-storage/staging-secrets
            iam.issuer=https://iam.staging.example.invalid
            iam.audience=file-storage-staging
            iam.jwks=/etc/file-storage/staging/jwks.json
            iam.snapshot-seconds=300
            iam.token-seconds=300
            provider.spool=/var/lib/file-storage/staging-spool
            session.seconds=300
            idempotency.lease-seconds=30
            handle.seconds=60
            operation.lease-seconds=60
            retry.seconds=5
            attempts=3
            outbox.batch=10
            worker.delay-ms=1000
            transfer.max-bytes=1048576
            transfer.concurrency=2
            transfer.buffer-bytes=16384
            transfer.timeout-seconds=15
            db.METADATA.login=fs_staging_metadata
            db.METADATA.ref=ref:staging/file-storage/db-metadata
            db.LOGICAL.login=fs_staging_logical
            db.LOGICAL.ref=ref:staging/file-storage/db-logical
            db.PURGE_PREPARATION.login=fs_staging_purge_preparation
            db.PURGE_PREPARATION.ref=ref:staging/file-storage/db-purge-preparation
            db.PLACEMENT.login=fs_staging_placement
            db.PLACEMENT.ref=ref:staging/file-storage/db-placement
            db.BACKEND.login=fs_staging_backend
            db.BACKEND.ref=ref:staging/file-storage/db-backend
            db.GRANT_READ.login=fs_staging_grant_read
            db.GRANT_READ.ref=ref:staging/file-storage/db-grant-read
            db.READINESS.login=fs_staging_readiness
            db.READINESS.ref=ref:staging/file-storage/db-readiness
            db.TRANSFER.login=fs_staging_transfer
            db.TRANSFER.ref=ref:staging/file-storage/db-transfer
            db.WORKER.login=fs_staging_worker
            db.WORKER.ref=ref:staging/file-storage/db-worker
            db.GOVERNANCE.login=fs_staging_governance
            db.GOVERNANCE.ref=ref:staging/file-storage/db-governance
            db.DISPATCHER.login=fs_staging_dispatcher
            db.DISPATCHER.ref=ref:staging/file-storage/db-dispatcher
            provider.count=2
            provider.1.kind=S3
            provider.1.endpoint=https://s3.staging.example.invalid
            provider.1.region=example-region
            provider.1.addressing=PATH
            provider.1.backend=00000000-0000-4000-8000-000000000001
            provider.1.revision=1
            provider.1.location=00000000-0000-4000-8000-000000000002
            provider.1.bucket=example-s3-staging
            provider.1.prefix=fixture/
            provider.1.ref=ref:staging/file-storage/s3
            provider.2.kind=OSS
            provider.2.endpoint=https://oss.staging.example.invalid
            provider.2.region=cn-example
            provider.2.addressing=VIRTUAL_HOST
            provider.2.backend=00000000-0000-4000-8000-000000000003
            provider.2.revision=1
            provider.2.location=00000000-0000-4000-8000-000000000004
            provider.2.bucket=example-oss-staging
            provider.2.prefix=fixture/
            provider.2.ref=ref:staging/file-storage/oss
            
            """.replace("/etc/file-storage/staging/db-ca.pem",ca.toString()).replace("/run/file-storage/staging-secrets",root.toString())
            .replace("/etc/file-storage/staging/jwks.json",jwks.toString()).replace("/var/lib/file-storage/staging-spool",spool.toString());
        writePrivate(config,content);
        for(var role:RuntimeDatabaseRoles.Role.values())writePrivate(root.resolve("db-"+role.name().toLowerCase(Locale.ROOT).replace('_','-')),"password=fixture-only\n");
        for(String name:List.of("s3","oss"))writePrivate(root.resolve(name),"access=fixture-only\nsecret=fixture-only\ntoken=fixture-only\n");
    }
    @Test void strictConfigurationRejectsUnknownDuplicateProductionAndUnsafeValues()throws Exception{
        var c=StagingBootstrapConfig.load(config);
        assertEquals(11,c.roles.size());assertEquals(2,c.locations.size());assertFalse(c.enabled);assertFalse(c.dispatch);
        assertEquals("StagingBootstrapConfig[redacted]",c.toString());
        for(String bad:List.of(content+"\nunknown=true",content+"\nenvironment=staging",content.replace("environment=staging","environment=production"),
            content.replace("dispatch.enabled=false","dispatch.enabled=true"),content.replace("db.host=db.staging.example.invalid","db.host=host?sslmode=disable"),
            content.replace("db.TRANSFER.ref=ref:staging/file-storage/db-transfer","db.TRANSFER.ref=ref:staging/file-storage/db-worker"),
            content.replace("provider.1.ref=ref:staging/file-storage/s3","provider.1.ref=ref:production/s3"),
            content.replace("operation.lease-seconds=60","operation.lease-seconds=2"),content.replace("provider.2.addressing=VIRTUAL_HOST","provider.2.addressing=PATH"))){
            writePrivate(config,bad);var failure=assertThrows(IllegalArgumentException.class,()->StagingBootstrapConfig.load(config));
            assertEquals("invalid staging configuration",failure.getMessage());assertNull(failure.getCause());
        }
    }
    @Test void ossCnameIsRejectedByPropertiesAndTypedPin()throws Exception{
        var admitted=StagingBootstrapConfig.load(config);
        var pin=admitted.locations.values().stream().filter(p->p.target().provider()==Provider.OSS).findFirst().orElseThrow();
        assertEquals(Addressing.VIRTUAL_HOST,pin.target().addressing());
        var cname=new Target(Provider.OSS,pin.target().endpoint(),pin.target().region(),Addressing.CNAME);
        assertThrows(IllegalArgumentException.class,()->new StagingRuntimeDependencies.Pin(cname,pin.location(),pin.credential()));
        writePrivate(config,content.replace("provider.2.addressing=VIRTUAL_HOST","provider.2.addressing=CNAME"));
        var failure=assertThrows(IllegalArgumentException.class,()->StagingBootstrapConfig.load(config));
        assertEquals("invalid staging configuration",failure.getMessage());
        assertNull(failure.getCause());
    }
    @Test void exactRevisionPropertiesPermitHistoricalAndCurrentPinsButRejectConflicts()throws Exception{
        String old=content.substring(content.indexOf("provider.1.kind="),content.indexOf("provider.2.kind="));
        String next=old.replace("provider.1.","provider.3.").replace("revision=1","revision=2")
                .replace("ref:staging/file-storage/s3","ref:staging/file-storage/s3-v2");
        String both=content.replace("provider.count=2","provider.count=3")+"\n"+next;
        writePrivate(config,both);
        var admitted=StagingBootstrapConfig.load(config);
        assertEquals(3,admitted.locations.size());
        var first=admitted.locations.entrySet().stream().filter(e->e.getKey().revision()==2).findFirst().orElseThrow();
        var oldKey=new StagingRuntimeDependencies.PinKey(first.getKey().backend(),1,first.getKey().location());
        assertEquals(admitted.locations.get(oldKey).location(),first.getValue().location());
        assertNotEquals(admitted.locations.get(oldKey).credential(),first.getValue().credential());
        for(String bad:List.of(both.replace("provider.3.revision=2","provider.3.revision=1"),
                both.replace("provider.3.revision=2","provider.3.revision=0"),
                both.replace("provider.3.revision=2\n",""),
                both.replace("provider.3.bucket=example-s3-staging","provider.3.bucket=wrong-bucket"),
                both.replace("provider.3.endpoint=https://s3.staging.example.invalid","provider.3.endpoint=https://other.example.invalid"),
                both.replace("provider.3.ref=ref:staging/file-storage/s3-v2","provider.3.ref=ref:staging/file-storage/db-worker"))){
            writePrivate(config,bad);
            assertThrows(IllegalArgumentException.class,()->StagingBootstrapConfig.load(config));
        }
    }
    @Test void mountedSecretsRejectTraversalSymlinksPermissionsAndUnexpectedFields()throws Exception{
        var secrets=new MountedStagingSecrets(root);
        assertEquals("fixture-only",secrets.databasePassword("ref:staging/file-storage/db-metadata"));
        try(var provider=secrets.provider("ref:staging/file-storage/s3")){
            assertEquals("fixture-only",provider.token());assertEquals("ProviderSecret[redacted]",provider.toString());
        }
        assertThrows(IllegalStateException.class,()->secrets.databasePassword("ref:staging/file-storage/../ca"));
        Files.createSymbolicLink(root.resolve("link"),root.resolve("db-metadata"));
        assertThrows(IllegalStateException.class,()->secrets.databasePassword("ref:staging/file-storage/link"));
        Files.setPosixFilePermissions(root.resolve("db-metadata"),PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(IllegalStateException.class,()->secrets.databasePassword("ref:staging/file-storage/db-metadata"));
        writePrivate(root.resolve("db-metadata"),"password=fixture-only\nextra=unexpected\n");
        assertThrows(IllegalStateException.class,()->secrets.databasePassword("ref:staging/file-storage/db-metadata"));
        var c=StagingBootstrapConfig.load(config);
        var ds=new StagingRoleDataSource(c,c.roles.get(RuntimeDatabaseRoles.Role.METADATA),secrets);
        assertEquals("08001",assertThrows(java.sql.SQLException.class,ds::getConnection).getSQLState());
        assertThrows(java.sql.SQLFeatureNotSupportedException.class,()->ds.getConnection("owner","fixture"));
    }
    @Test void prepareRequiresTrustedPortsAndRefusesDisabledOrUnreadyStartup()throws Exception{
        var integration=new StagingBootstrap.Integrations((a,t)->false,(t,m)->null,(i,s,a)->Optional.empty(),
            new ApiRuntimeDependencies.PolicyAdministration((i,s,a,t)->Optional.empty(),(a,s,x)->false,(a,s,l)->false),
            (i,s,a)->Optional.empty(),(a,x,b)->false,c->null,
            (stream,size,deadline)->new ContentScanner.Verdict(Scan.UNAVAILABLE,null),event->{throw new IllegalStateException("fixture");},
            new ActorReference(ActorType.SERVICE,"fixture-worker",null,null),()->false);
        var prepared=StagingBootstrap.prepare(config,integration,Clock.systemUTC());
        assertFalse(prepared.ready());assertThrows(IllegalStateException.class,prepared::start);
        writePrivate(config,content.replace("staging.enabled=false","staging.enabled=true"));
        prepared=StagingBootstrap.prepare(config,integration,Clock.systemUTC());
        assertFalse(prepared.ready());assertThrows(IllegalStateException.class,prepared::start);
        Files.delete(root.resolve("db-worker"));
        assertThrows(IllegalStateException.class,()->StagingBootstrap.prepare(config,integration,Clock.systemUTC()));
    }
}
