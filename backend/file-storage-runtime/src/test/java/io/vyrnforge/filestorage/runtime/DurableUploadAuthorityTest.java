package io.vyrnforge.filestorage.runtime;

import io.vyrnforge.filestorage.application.FileApplication.*;
import io.vyrnforge.filestorage.application.BackendValidator;
import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.filestorage.persistence.*;
import io.vyrnforge.filestorage.runtime.transfer.*;
import io.vyrnforge.filestorage.security.SecuredFiles;
import io.vyrnforge.filestorage.security.SecuredFiles.*;
import io.vyrnforge.platform.kernel.context.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import io.vyrnforge.filestorage.s3.S3StorageProvider;
import io.vyrnforge.filestorage.oss.OssStorageProvider;
import io.vyrnforge.filestorage.oss.OssWireFixture;
import io.vyrnforge.filestorage.spi.StorageProvider;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** Real disposable PostgreSQL, real durable grant and claim repositories; synthetic identity only. */
class DurableUploadAuthorityTest {
    private static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");
    private static PGSimpleDataSource source;
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String ISS = "https://iam.example.test", AUD = "file-storage-test";
    private static final TenantId TENANT = new TenantId(UUID.fromString("01900000-0000-7000-8000-000000000001"));
    private static final TenantId OTHER = new TenantId(UUID.fromString("01900000-0000-7000-8000-000000000002"));
    private static final ActorReference USER = new ActorReference(ActorType.USER, "fixture-user", null, null);
    private static final ActorReference ADMIN = new ActorReference(ActorType.USER, "fixture-admin", null, null);
    private static final Scope SCOPE = new Scope(TENANT, "asset", "attachment");
    private static final Target TARGET = new Target(Provider.S3, URI.create("https://s3.example.test"), "test", Addressing.PATH);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    @TempDir Path spool;
    private BackendId backend;
    private LocationId location;
    private SecuredFiles secured;
    private View view;

    @BeforeAll static void start() {
        DB.start();
        source = new PGSimpleDataSource();
        source.setURL(DB.getJdbcUrl()); source.setUser(DB.getUsername()); source.setPassword(DB.getPassword());
        source.setConnectTimeout(3); source.setSocketTimeout(5);
        RuntimeComposition.migrate(source);
    }
    @AfterAll static void stop() { DB.stop(); }

    @BeforeEach void seed() throws Exception {
        sql("TRUNCATE fs.backend,fs.policy_revision,fs.file,fs.file_grant,fs_kernel.platform_idempotency,fs_kernel.platform_audit_event,fs_kernel.platform_event_outbox CASCADE");
        var registry = new JdbcRegistry(source, CLOCK);
        var config = new JdbcConfiguration(source, CLOCK);
        var correlation = CorrelationContext.create();
        backend = registry.registerBackend(ADMIN, correlation, TARGET, new CredentialRef("ref:fixture/v1"));
        location = registry.registerLocation(ADMIN, correlation, backend, "fixture-bucket", "private-prefix");
        registry.validateDraft(ADMIN, correlation, backend, 1, Set.of(TARGET), candidate -> {
            Map<LocationId, Set<BackendValidator.Check>> checks = new HashMap<>();
            for (var item : candidate.locations()) checks.put(item.id(), EnumSet.allOf(BackendValidator.Check.class));
            return new BackendValidator.Report(candidate.backend(), candidate.revision(), checks);
        });
        config.activateBackend(ADMIN, correlation, backend, 0, 1);
        config.publishPolicy(ADMIN, correlation, SCOPE, 0, List.of(new JdbcConfiguration.PolicyRule(location, 0, true)));
        secured = secured(CLOCK);
        grant("fixture-user", "asset", "INITIATE", "INITIATE_PURPOSE", null);
        grant("fixture-user", "asset", "READ_SESSION", "OWNED_FILE", null);
        view = secured.initiate("user", TENANT, null, new Initiate("attachment","sample.txt",5,"text/plain","fixture-key")).value();
    }

    private SecuredFiles secured(Clock clock) {
        return RuntimeComposition.secured(source, new Trust(ISS,AUD), bearer -> {
            if (!Set.of("user","other","other-app","other-tenant").contains(bearer)) throw new IllegalArgumentException("private verifier detail");
            return new VerifiedAccess(ISS,bearer,Set.of(AUD),TokenKind.ACCESS,NOW,NOW.plusSeconds(3600),NOW);
        }, (issuer, subject, audience) -> Optional.of(new Binding(
                "other".equals(subject) ? new ActorReference(ActorType.USER,"fixture-other",null,null) : USER,
                "other-tenant".equals(subject) ? OTHER : TENANT,
                "other-app".equals(subject) ? "itsm" : "asset", Set.of(Action.values()))),
                (actor, tenant) -> true,
                (tenant,module) -> new ModuleEntitlement(TENANT.value(),"file-storage",true,NOW),
                new JdbcGrants(source,clock,100,5), clock,Duration.ofMinutes(5),Duration.ofSeconds(30));
    }

    private DurableUploadAuthority authority() { return authority(CLOCK); }
    private DurableUploadAuthority authority(Clock clock) {
        return new DurableUploadAuthority(secured(clock), new JdbcUploadTransfers(source,clock,Duration.ofMinutes(1),5));
    }

    private void grant(String actor, String consumer, String action, String scope, String file) throws Exception {
        sql("""
                INSERT INTO fs.file_grant(grant_id,tenant_id,actor,consumer,purpose,action,scope,file_id,not_before,expires_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(),TENANT.value(),actor,consumer,"attachment",action,scope,file,
                NOW.minusSeconds(1).atOffset(ZoneOffset.UTC),NOW.plusSeconds(3600).atOffset(ZoneOffset.UTC));
    }
    private void allow() throws Exception { grant("fixture-user","asset","UPLOAD_TRANSFER","OWNED_FILE",null); }
    private JdbcUploadTransfers.Issued issue() { return authority().issue("user",TENANT,view.session(),null); }
    private static void sql(String statement,Object... args) throws Exception {
        try (var c=source.getConnection();var ps=c.prepareStatement(statement)) {
            for(int i=0;i<args.length;i++) ps.setObject(i+1,args[i]); ps.executeUpdate();
        }
    }
    private static long scalar(String statement,Object... args) throws Exception {
        try(var c=source.getConnection();var ps=c.prepareStatement(statement)) {
            for(int i=0;i<args.length;i++) ps.setObject(i+1,args[i]);
            try(var rows=ps.executeQuery()){ rows.next();return rows.getLong(1); }
        }
    }
    private static void deny(DurableUploadAuthority a,String proof,String handle) {
        var ex=assertThrows(TransferFailure.class,()->a.claim(proof,handle,TransferInstruction.Action.UPLOAD));
        assertEquals(TransferFailure.Code.DENIED,ex.code()); assertNull(ex.getCause());
    }

    @Test void explicitGrantAndExactIdentityAndPinRequiredAcrossInstances() throws Exception {
        assertThrows(RuntimeException.class,this::issue);
        assertEquals(0,scalar("SELECT count(*) FROM fs.upload_transfer_handle"));
        allow();
        var issued=issue();
        assertEquals(43,issued.handle().length());
        assertEquals("Issued[redacted]",issued.toString());
        assertEquals(NOW.plusSeconds(60),issued.expiresAt());
        assertEquals(1,scalar("SELECT count(*) FROM fs.upload_transfer_handle WHERE octet_length(handle_hash)=32 AND claimed_at IS NULL"));
        assertThrows(RuntimeException.class,this::issue);
        for(String proof:List.of("other","other-app","other-tenant","wrong")) deny(authority(),proof,issued.handle());
        assertThrows(TransferFailure.class,()->authority().claim("user",issued.handle(),TransferInstruction.Action.DOWNLOAD));
        assertEquals(0,scalar("SELECT count(*) FROM fs.upload_transfer_handle WHERE claimed_at IS NOT NULL"));
        var pin=authority().claim("user",issued.handle(),TransferInstruction.Action.UPLOAD);
        assertEquals(TENANT,pin.tenant());assertEquals(USER.actorId(),pin.actorId());assertEquals("asset",pin.application());
        assertEquals(view.session(),pin.session());assertEquals(view.version(),pin.version());
        assertEquals(view.file().fileId(),pin.file().value());assertEquals(backend,pin.backend());
        assertEquals(location,pin.locator().location());assertEquals(1,pin.revision());assertEquals(5,pin.size());
        deny(authority(),"user",issued.handle());
        assertEquals(1,scalar("SELECT count(*) FROM fs_kernel.platform_event_outbox WHERE event_type='file-storage.transfer.upload.claimed'"));
        assertEquals(1,scalar("SELECT count(*) FROM fs_kernel.platform_audit_event WHERE action='file-storage.transfer.upload.claimed'"));
        assertEquals(0,scalar("SELECT count(*) FROM fs_kernel.platform_event_outbox WHERE payload_json::text LIKE ?", "%"+issued.handle()+"%"));
    }

    @Test void concurrentClaimsHaveOneWinnerAndOneDurableIntent() throws Exception {
        allow();var issued=issue();var start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            Callable<Boolean> claim=()->{start.await();try{authority().claim("user",issued.handle(),TransferInstruction.Action.UPLOAD);return true;}catch(TransferFailure ex){return false;}};
            var one=executor.submit(claim);var two=executor.submit(claim);start.countDown();
            assertNotEquals(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,scalar("SELECT count(*) FROM fs.upload_transfer_handle WHERE claimed_at IS NOT NULL"));
        assertEquals(1,scalar("SELECT count(*) FROM fs_kernel.platform_event_outbox WHERE event_type='file-storage.transfer.upload.claimed'"));
    }

    @Test void currentGrantRevocationAndExpiryDenyWithoutConsumption() throws Exception {
        allow();var issued=issue();
        sql("UPDATE fs.file_grant SET revoked=true WHERE action='UPLOAD_TRANSFER'");
        deny(authority(),"user",issued.handle());
        // Even a caller authorized before revocation cannot consume against a now-revoked local grant.
        var handles = new JdbcUploadTransfers(source,CLOCK,Duration.ofMinutes(1),5);
        var caller = new Caller(TENANT,USER.actorId(),"asset");
        var denied = assertThrows(Failure.class,()->handles.claim(issued.handle(),caller,view.session(),CorrelationContext.create()));
        assertEquals(Code.DENIED,denied.code());
        sql("UPDATE fs.file_grant SET revoked=false WHERE action='UPLOAD_TRANSFER'");
        deny(authority(Clock.fixed(NOW.plusSeconds(60),ZoneOffset.UTC)),"user",issued.handle());
        assertEquals(0,scalar("SELECT count(*) FROM fs.upload_transfer_handle WHERE claimed_at IS NOT NULL"));
        assertEquals(0,scalar("SELECT count(*) FROM fs_kernel.platform_event_outbox WHERE event_type='file-storage.transfer.upload.claimed'"));
        assertThrows(SQLException.class,()->sql("UPDATE fs.upload_transfer_handle SET actor='different'"));
    }

    @Test void requiredClaimIntentFailureRollsBackAndRetryCanClaim() throws Exception {
        allow();var issued=issue();
        sql("ALTER TABLE fs_kernel.platform_event_outbox ADD CONSTRAINT reject_test_claim CHECK (event_type <> 'file-storage.transfer.upload.claimed') NOT VALID");
        try{
            deny(authority(),"user",issued.handle());
            assertEquals(0,scalar("SELECT count(*) FROM fs.upload_transfer_handle WHERE claimed_at IS NOT NULL"));
            assertEquals(0,scalar("SELECT count(*) FROM fs_kernel.platform_audit_event WHERE action='file-storage.transfer.upload.claimed'"));
        }finally{sql("ALTER TABLE fs_kernel.platform_event_outbox DROP CONSTRAINT reject_test_claim");}
        assertEquals(view.session(),authority().claim("user",issued.handle(),TransferInstruction.Action.UPLOAD).session());
    }

    private static final class FixtureCredential implements AutoCloseable {
        final CredentialRef reference;
        final AtomicInteger closes;
        FixtureCredential(CredentialRef reference, AtomicInteger closes) {
            this.reference = reference; this.closes = closes;
        }
        @Override public void close() { closes.incrementAndGet(); }
    }

    private void rotate() {
        var registry = new JdbcRegistry(source, CLOCK);
        var ctx = CorrelationContext.create();
        assertEquals(2, registry.reviseCredential(ADMIN, ctx, backend, 1, new CredentialRef("ref:fixture/v2")));
        registry.validateDraft(ADMIN, ctx, backend, 2, Set.of(TARGET), candidate -> {
            Map<LocationId, Set<BackendValidator.Check>> checks = new HashMap<>();
            for (var item : candidate.locations()) checks.put(item.id(), EnumSet.allOf(BackendValidator.Check.class));
            return new BackendValidator.Report(candidate.backend(), candidate.revision(), checks);
        });
        new JdbcConfiguration(source, CLOCK).activateBackend(ADMIN, ctx, backend, 1, 2);
    }

    private static S3Client client(GenericContainer<?> server, String user, String password) {
        return S3Client.builder()
                .endpointOverride(URI.create("http://" + server.getHost() + ":" + server.getMappedPort(9000)))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(user,password)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClientBuilder(ApacheHttpClient.builder().connectionTimeout(Duration.ofSeconds(2))
                        .socketTimeout(Duration.ofSeconds(3)).connectionAcquisitionTimeout(Duration.ofSeconds(2)).maxConnections(8))
                .overrideConfiguration(config -> config.retryPolicy(RetryPolicy.none()).apiCallTimeout(Duration.ofSeconds(15)))
                .build();
    }

    private static String objectKey() throws SQLException {
        try (var c=source.getConnection();var ps=c.prepareStatement("SELECT object_key FROM fs.file_version");
             var rows=ps.executeQuery()) { assertTrue(rows.next()); return rows.getString(1); }
    }

    @Test void durableHttpUploadUsesRealMinioAndOldCredentialPinAfterRotation() throws Exception {
        allow();
        var issued = issue();
        rotate();
        String user = "test-" + UUID.randomUUID(), password = UUID.randomUUID().toString();
        try (var server = new GenericContainer<>(DockerImageName.parse("file-storage-test/minio:2025-04-22-verified"))
                .withImagePullPolicy(image -> false).withEnv("MINIO_ROOT_USER",user).withEnv("MINIO_ROOT_PASSWORD",password)
                .withCommand("server","/data").withExposedPorts(9000).withStartupAttempts(1)
                .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000).withStartupTimeout(Duration.ofMinutes(2)))) {
            server.start();
            try (var inspection = client(server,user,password)) {
                inspection.createBucket(request -> request.bucket("fixture-bucket"));
                var credentials = new AtomicInteger();
                var credentialCloses = new AtomicInteger();
                var adapterCloses = new AtomicInteger();
                var resolver = new RegistryTransferProviderResolver<FixtureCredential>(
                        new JdbcRegistry(source,CLOCK), ref -> {
                            assertEquals(new CredentialRef("ref:fixture/v1"),ref);
                            credentials.incrementAndGet();
                            return new FixtureCredential(ref,credentialCloses);
                        }, Set.of(TARGET), Map.of(Provider.S3, (target, resolvedLocation, revision, credential) -> {
                            assertEquals(TARGET,target); assertEquals(location,resolvedLocation.id());
                            assertEquals(backend,resolvedLocation.backend()); assertEquals(1,revision);
                            assertEquals(new CredentialRef("ref:fixture/v1"),credential.reference);
                            // Fixture-only explicit mapping of approved logical HTTPS target to local HTTP container.
                            // No runtime endpoint override or production credentials.
                            var sdk = client(server,user,password);
                            S3StorageProvider adapter;
                            try {
                                adapter = new S3StorageProvider(sdk,Map.of(resolvedLocation.id(),resolvedLocation),spool,
                                        1024*1024,1,Duration.ofSeconds(15),128,Duration.ofMinutes(1));
                            } catch (RuntimeException ex) { sdk.close(); throw ex; }
                            try { adapter.validateUnversioned(new StorageProvider.Call(Duration.ofSeconds(10))); }
                            catch (RuntimeException ex) { adapter.close(); throw ex; }
                            return new TransferProviderResolver.Lease() {
                                @Override public StorageProvider provider(){return adapter;}
                                @Override public void close(){try{adapter.close();}finally{adapterCloses.incrementAndGet();}}
                            };
                        }));
                var gateway = new PrivateTransferGateway(DurableDownloadAuthority.combine(authority(),downloads(CLOCK)),resolver,
                        new TransferLimits(1024*1024,1,1024,Duration.ofSeconds(15)),CLOCK);
                var app = RuntimeApplication.application(TransferHttpConfiguration.class);
                app.addInitializers(ctx -> ctx.getBeanFactory().registerSingleton("privateTransferGateway",gateway));
                try (var ctx = app.run("--server.port=0","--fs.role=TRANSFER");
                     var http = HttpClient.newHttpClient()) {
                    URI uri = URI.create("http://127.0.0.1:"+ctx.getEnvironment().getProperty("local.server.port")+"/transfer/v1/upload");
                    byte[] content = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                            .header("Authorization","Bearer user").header("X-Transfer-Handle",issued.handle())
                            .header("Content-Type","application/octet-stream")
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(content)).build();
                    var response = http.send(request,HttpResponse.BodyHandlers.ofString());
                    assertEquals(204,response.statusCode(),response.body());
                    assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
                    assertTrue(response.headers().firstValue("Location").isEmpty());
                    assertArrayEquals(content,inspection.getObjectAsBytes(req -> req.bucket("fixture-bucket").key(objectKeyUnchecked())).asByteArray());
                    assertEquals(403,http.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
                    assertEquals(1,credentials.get()); assertEquals(1,credentialCloses.get()); assertEquals(1,adapterCloses.get());
                    var verifier=verification(resolver);
                    assertTrue(verifier.verify("user",TENANT,view.session(),sha256(content),null).integrityMatched());
                    assertTrue(verifier.verify("user",TENANT,view.session(),sha256(content),null).integrityMatched());
                    assertEquals(2,credentials.get());assertEquals(2,credentialCloses.get());assertEquals(2,adapterCloses.get());
                    assertEquals(1,scalar("SELECT count(*) FROM fs.upload_integrity_verification WHERE verified_at IS NOT NULL AND observed_size=5"));
                    assertEquals(1,scalar("SELECT count(*) FROM fs.upload_transfer_handle WHERE claimed_at IS NOT NULL"));
                    assertEquals(1,scalar("SELECT count(*) FROM fs.file_version WHERE state='PENDING'"));
                    assertEquals(1,scalar("SELECT count(*) FROM fs.upload_session WHERE state='INITIATED'"));
                    try(var files=Files.list(spool)){assertEquals(0,files.count());}
                    var scans=scans(CLOCK,3);
                    new UploadCompletion(secured(CLOCK),scans).begin("user",TENANT,view.session(),null);
                    assertEquals(UploadScanWorker.Outcome.COMPLETED,worker(scans,resolver,scanner(Scan.CLEAN,"text/plain"),CLOCK).runOne());
                    assertEquals(VersionState.AVAILABLE,secured(CLOCK).session("user",TENANT,null,view.session()).value().versionState());
                    assertEquals(3,credentialCloses.get());assertEquals(3,adapterCloses.get());
                    grant("fixture-user","asset","DOWNLOAD_TRANSFER","OWNED_FILE",null);
                    // Change the policy to another registered location; historical placement must survive.
                    var registry=new JdbcRegistry(source,CLOCK);
                    var configuration=new JdbcConfiguration(source,CLOCK);
                    var correlation=CorrelationContext.create();
                    var replacementTarget=new Target(Provider.S3,URI.create("https://replacement.example.test"),"test",Addressing.PATH);
                    var replacement=registry.registerBackend(ADMIN,correlation,replacementTarget,new CredentialRef("ref:replacement/v1"));
                    var newLocation=registry.registerLocation(ADMIN,correlation,replacement,"replacement-bucket","new-prefix");
                    registry.validateDraft(ADMIN,correlation,replacement,1,Set.of(replacementTarget),candidate->{
                        Map<LocationId,Set<BackendValidator.Check>> checks=new HashMap<>();
                        for(var item:candidate.locations())checks.put(item.id(),EnumSet.allOf(BackendValidator.Check.class));
                        return new BackendValidator.Report(candidate.backend(),candidate.revision(),checks);
                    });
                    configuration.activateBackend(ADMIN,correlation,replacement,0,1);
                    configuration.publishPolicy(ADMIN,correlation,SCOPE,1,List.of(new JdbcConfiguration.PolicyRule(newLocation,0,true)));
                    var download=downloads(CLOCK).issue("user",TENANT,new FileId(view.file().fileId()),view.version(),null).value();
                    var requestDownload=downloadRequest(uri.resolve("/transfer/v1/download").toString(),"user",download.handle());
                    var resultDownload=http.send(requestDownload,HttpResponse.BodyHandlers.ofByteArray());
                    assertEquals(200,resultDownload.statusCode());assertArrayEquals(content,resultDownload.body());
                    assertEquals(403,http.send(requestDownload,HttpResponse.BodyHandlers.ofString()).statusCode());
                    assertEquals(4,credentialCloses.get());assertEquals(4,adapterCloses.get());
                    grant("fixture-user","asset","DELETE_FILE","OWNED_FILE",null);
                    grant("fixture-user","asset","RESTORE_FILE","OWNED_FILE",null);
                    var old=downloads(CLOCK).issue("user",TENANT,fileId(),view.version(),null).value();
                    var oldRequest=downloadRequest(uri.resolve("/transfer/v1/download").toString(),"user",old.handle());
                    secured.lifecycle("user",TENANT,null,fileId(),Action.DELETE_FILE,0);
                    assertEquals(403,http.send(oldRequest,HttpResponse.BodyHandlers.ofString()).statusCode());
                    assertEquals(4,credentials.get(),"deleted claim must not open provider");
                    secured.lifecycle("user",TENANT,null,fileId(),Action.RESTORE_FILE,1);
                    assertEquals(403,http.send(oldRequest,HttpResponse.BodyHandlers.ofString()).statusCode());
                    assertEquals(4,credentials.get(),"stale claim must not open provider");
                    var fresh=downloads(CLOCK).issue("user",TENANT,fileId(),view.version(),null).value();
                    var again=http.send(downloadRequest(uri.resolve("/transfer/v1/download").toString(),"user",fresh.handle()),HttpResponse.BodyHandlers.ofByteArray());
                    assertEquals(200,again.statusCode());assertArrayEquals(content,again.body());
                    assertEquals(5,credentialCloses.get());assertEquals(5,adapterCloses.get());
                }
            }
        }
    }

    private static String objectKeyUnchecked() {
        try { return objectKey(); } catch(SQLException ex) { throw new IllegalStateException("fixture lookup failed"); }
    }

    @Test void resolverRejectsMissingPinUnapprovedTargetAndCleansUpFactoryFailure() throws Exception {
        allow(); var issued=issue();
        var instruction=authority().claim("user",issued.handle(),TransferInstruction.Action.UPLOAD);
        var registry=new JdbcRegistry(source,CLOCK);
        var opened=new AtomicInteger();var closed=new AtomicInteger();
        io.vyrnforge.filestorage.application.CredentialResolver<FixtureCredential> credentials=ref->{
            opened.incrementAndGet(); return new FixtureCredential(ref,closed);
        };
        Map<Provider,RegistryTransferProviderResolver.AdapterFactory<FixtureCredential>> factories=Map.of(
                Provider.S3,(target,loc,revision,credential)->{throw new IllegalStateException("private factory detail");});
        var denied=new RegistryTransferProviderResolver<>(registry,credentials,Set.of(),factories);
        assertEquals(TransferFailure.Code.UNAVAILABLE,assertThrows(TransferFailure.class,()->denied.resolve(instruction)).code());
        var unsupported=new RegistryTransferProviderResolver<>(registry,credentials,Set.of(TARGET),Map.of());
        assertThrows(TransferFailure.class,()->unsupported.resolve(instruction));
        var resolver=new RegistryTransferProviderResolver<>(registry,credentials,Set.of(TARGET),factories);
        var wrong=new TransferInstruction(instruction.tenant(),instruction.actorId(),instruction.application(),
                instruction.action(),instruction.file(),instruction.version(),instruction.session(),backend,99,
                instruction.locator(),instruction.size(),instruction.expiresAt());
        assertThrows(TransferFailure.class,()->resolver.resolve(wrong));
        assertEquals(0,opened.get());
        var failure=assertThrows(TransferFailure.class,()->resolver.resolve(instruction));
        assertEquals("UNAVAILABLE",failure.getMessage());assertNull(failure.getCause());
        assertEquals(1,opened.get());assertEquals(1,closed.get());
        var outside=new TransferInstruction(instruction.tenant(),instruction.actorId(),instruction.application(),
                instruction.action(),instruction.file(),instruction.version(),instruction.session(),backend,1,
                new Locator(location,"outside-prefix/object",null),instruction.size(),instruction.expiresAt());
        assertThrows(TransferFailure.class,()->resolver.resolve(outside));
        assertEquals(2,opened.get());assertEquals(2,closed.get());
    }

    @Test void nativeOssCombinedRuntimeSharesMetadataAndDurableTransferAuthority() throws Exception {
        Target ossTarget = new Target(Provider.OSS, URI.create("https://oss.example.test"), "cn-hangzhou", Addressing.PATH);
        var registry = new JdbcRegistry(source,CLOCK);
        var config = new JdbcConfiguration(source,CLOCK);
        var correlation = CorrelationContext.create();
        BackendId oss = registry.registerBackend(ADMIN,correlation,ossTarget,new CredentialRef("ref:fixture/oss:v1"));
        LocationId ossLocation = registry.registerLocation(ADMIN,correlation,oss,"fixture-oss","private-oss");
        registry.validateDraft(ADMIN,correlation,oss,1,Set.of(ossTarget),candidate->{
            Map<LocationId,Set<BackendValidator.Check>> checks=new HashMap<>();
            for(var item:candidate.locations()) checks.put(item.id(),EnumSet.allOf(BackendValidator.Check.class));
            return new BackendValidator.Report(candidate.backend(),candidate.revision(),checks);
        });
        config.activateBackend(ADMIN,correlation,oss,0,1);
        config.publishPolicy(ADMIN,correlation,SCOPE,1,List.of(new JdbcConfiguration.PolicyRule(ossLocation,0,true)));
        allow();
        grant("fixture-user","asset","READ_METADATA","OWNED_FILE",null);
        try(var fixture=new OssWireFixture()) {
            var opened=new AtomicInteger();var closed=new AtomicInteger();var adapterCloses=new AtomicInteger();
            var resolver=new RegistryTransferProviderResolver<FixtureCredential>(registry,ref->{
                assertEquals(new CredentialRef("ref:fixture/oss:v1"),ref);
                opened.incrementAndGet();return new FixtureCredential(ref,closed);
            },Set.of(TARGET,ossTarget),Map.of(Provider.OSS,(target,loc,revision,credential)->{
                assertEquals(ossTarget,target);assertEquals(oss,loc.backend());assertEquals(ossLocation,loc.id());assertEquals(1,revision);
                var sdk=fixture.client();
                OssStorageProvider adapter;
                try {adapter=new OssStorageProvider(sdk,Map.of(loc.id(),loc),spool,1024*1024,1,Duration.ofSeconds(15),128,Duration.ofMinutes(1),2);}
                catch(RuntimeException ex){sdk.shutdown();throw ex;}
                try{adapter.validateUnversioned(new StorageProvider.Call(Duration.ofSeconds(10)));}
                catch(RuntimeException ex){adapter.close();throw ex;}
                return new TransferProviderResolver.Lease(){
                    @Override public StorageProvider provider(){return adapter;}
                    @Override public void close(){try{adapter.close();}finally{adapterCloses.incrementAndGet();}}
                };
            }));
            var dependencies=new ApiRuntimeDependencies(source,CLOCK,Duration.ofMinutes(5),Duration.ofSeconds(30),
                new Trust(ISS,AUD),bearer->{
                    if(!Set.of("user","admin").contains(bearer))throw new IllegalArgumentException("fixture proof");
                    return new VerifiedAccess(ISS,bearer,Set.of(AUD),TokenKind.ACCESS,NOW,NOW.plusSeconds(3600),NOW);
                },(actor,tenant)->TENANT.value().equals(tenant),
                (tenant,module)->new ModuleEntitlement(TENANT.value(),"file-storage",true,NOW),
                new ApiRuntimeDependencies.ConsumerAuthority((issuer,subject,audience)->"user".equals(subject)
                    ?Optional.of(new Binding(USER,TENANT,"asset",Set.of(Action.values()))):Optional.empty(),
                    new JdbcGrants(source,CLOCK,100,5)),
                new ApiRuntimeDependencies.PolicyAdministration((issuer,subject,audience,tenant)->"admin".equals(subject)&&TENANT.equals(tenant)
                    ?Optional.of(ADMIN):Optional.empty(),(actor,scope,action)->ADMIN.equals(actor)&&SCOPE.equals(scope),
                    (actor,scope,locations)->ADMIN.equals(actor)&&SCOPE.equals(scope)),
                new ApiRuntimeDependencies.BackendAuthority((issuer,subject,audience)->"admin".equals(subject)?Optional.of(ADMIN):Optional.empty(),
                    (actor,action,id)->ADMIN.equals(actor),Set.of(TARGET,ossTarget),candidate->{
                        Map<LocationId,Set<BackendValidator.Check>> checks=new HashMap<>();
                        for(var item:candidate.locations())checks.put(item.id(),EnumSet.allOf(BackendValidator.Check.class));
                        return new BackendValidator.Report(candidate.backend(),candidate.revision(),checks);
                    }),()->true);
            var scanRepo=scans(CLOCK,3);
            var work=completionWork(CLOCK,scanRepo);
            var app=RuntimeApplication.application(ApiRuntimeConfiguration.class,TransferHttpConfiguration.class,
                io.vyrnforge.filestorage.runtime.http.UploadCommandHttpConfiguration.class,
                io.vyrnforge.filestorage.runtime.http.DownloadCommandHttpConfiguration.class);
            // Both route groups use the same secured facade; no separate metadata authority.
            app.addInitializers(ctx->{
                ctx.getBeanFactory().registerSingleton("apiRuntimeDependencies",dependencies);
                ((org.springframework.context.support.GenericApplicationContext)ctx).registerBean("downloadAuthority",DurableDownloadAuthority.class,()->new DurableDownloadAuthority(
                    ctx.getBean(SecuredFiles.class),new JdbcDownloadTransfers(source,CLOCK,Duration.ofMinutes(1),5)));
                ((org.springframework.context.support.GenericApplicationContext)ctx).registerBean("completionCommands",CompletionCommands.class,()->new CompletionCommands(
                    ctx.getBean(SecuredFiles.class),new JdbcUploadTransfers(source,CLOCK,Duration.ofMinutes(1),5),work));
                ((org.springframework.context.support.GenericApplicationContext)ctx).registerBean("durableUploadAuthority",DurableUploadAuthority.class,()->new DurableUploadAuthority(
                    ctx.getBean(SecuredFiles.class),new JdbcUploadTransfers(source,CLOCK,Duration.ofMinutes(1),5)));
                ((org.springframework.context.support.GenericApplicationContext)ctx).registerBean("privateTransferGateway",PrivateTransferGateway.class,()->new PrivateTransferGateway(
                    DurableDownloadAuthority.combine(ctx.getBean(DurableUploadAuthority.class),ctx.getBean(DurableDownloadAuthority.class)),resolver,new TransferLimits(1024*1024,1,1024,Duration.ofSeconds(15)),CLOCK));
            });
            try(var ctx=app.run("--server.port=0","--fs.role=COMBINED");var http=HttpClient.newHttpClient()){
                String base="http://127.0.0.1:"+ctx.getEnvironment().getProperty("local.server.port");
                byte[] payload=new byte[32768];new Random(42).nextBytes(payload);
                String body="{\"purpose\":\"attachment\",\"name\":\"oss.bin\",\"expectedSize\":"+payload.length+",\"mimeType\":\"application/octet-stream\"}";
                var initiate=HttpRequest.newBuilder(URI.create(base+"/v1/uploads")).header("Authorization","Bearer user")
                    .header("X-Tenant-Id",TENANT.value().toString()).header("Idempotency-Key","oss-combined")
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
                var result=http.send(initiate,HttpResponse.BodyHandlers.ofString());assertEquals(200,result.statusCode(),result.body());
                OpenApiContract.assertResponse(result);
                var json=tools.jackson.databind.json.JsonMapper.builder().build().readTree(result.body());
                var session=new SessionId(UUID.fromString(json.get("sessionId").asText()));
                var file=json.get("file").get("fileId").asText();
                String commandBase=base+"/v1/uploads/"+session.value();
                var issued=http.send(command(commandBase+"/transfer-handle","user","{}"),HttpResponse.BodyHandlers.ofString());
                assertEquals(200,issued.statusCode(),issued.body());OpenApiContract.assertResponse(issued);
                var handle=tools.jackson.databind.json.JsonMapper.builder().build().readTree(issued.body()).get("handle").asText();
                assertEquals(409,http.send(command(commandBase+"/transfer-handle","user","{}"),HttpResponse.BodyHandlers.ofString()).statusCode());
                String completionBody="{\"sha256\":\""+sha256(payload)+"\"}";
                assertEquals(403,http.send(command(commandBase+"/complete","user",completionBody),HttpResponse.BodyHandlers.ofString()).statusCode());
                var upload=HttpRequest.newBuilder(URI.create(base+"/transfer/v1/upload")).header("Authorization","Bearer user")
                    .header("X-Transfer-Handle",handle).header("Content-Type","application/octet-stream")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
                var response=http.send(upload,HttpResponse.BodyHandlers.ofString());
                assertEquals(204,response.statusCode(),response.body());assertTrue(response.headers().firstValue("Location").isEmpty());
                assertEquals(403,http.send(upload,HttpResponse.BodyHandlers.ofString()).statusCode());
                String key;
                try(var connection=source.getConnection();var ps=connection.prepareStatement("SELECT object_key FROM fs.file_version WHERE file_id=?")){
                    ps.setString(1,file);try(var rows=ps.executeQuery()){assertTrue(rows.next());key=rows.getString(1);}
                }
                assertArrayEquals(payload,fixture.bytes("fixture-oss",key));
                assertEquals(1,opened.get());assertEquals(1,closed.get());assertEquals(1,adapterCloses.get());
                var accepted=http.send(command(commandBase+"/complete","user",completionBody),HttpResponse.BodyHandlers.ofString());
                assertEquals(202,accepted.statusCode(),accepted.body());OpenApiContract.assertResponse(accepted);
                assertEquals(accepted.body(),http.send(command(commandBase+"/complete","user",completionBody),HttpResponse.BodyHandlers.ofString()).body());
                assertEquals(409,http.send(command(commandBase+"/complete","user","{\"sha256\":\""+"0".repeat(64)+"\"}"),HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(1,opened.get(),"HTTP completion must not read bytes");
                assertEquals(0,scalar("SELECT count(*) FROM fs.upload_integrity_verification WHERE verified_at IS NOT NULL"));
                try(var files=Files.list(spool)){assertEquals(0,files.count());}
                var metadata=HttpRequest.newBuilder(URI.create(base+"/v1/files/"+file)).header("Authorization","Bearer user")
                    .header("X-Tenant-Id",TENANT.value().toString()).GET().build();
                var read=http.send(metadata,HttpResponse.BodyHandlers.ofString());assertEquals(200,read.statusCode(),read.body());
                OpenApiContract.assertResponse(read);assertFalse(read.body().contains("private-oss"));assertFalse(read.body().contains(handle));
                var download=HttpRequest.newBuilder(URI.create(base+"/transfer/v1/download")).header("Authorization","Bearer user")
                    .header("X-Transfer-Handle",handle).GET().build();
                assertEquals(403,http.send(download,HttpResponse.BodyHandlers.ofString()).statusCode());
                var admin=HttpRequest.newBuilder(URI.create(base+"/v1/admin/backends")).header("Authorization","Bearer admin").GET().build();
                var adminResult=http.send(admin,HttpResponse.BodyHandlers.ofString());assertEquals(200,adminResult.statusCode());OpenApiContract.assertResponse(adminResult);
                assertEquals(503,http.send(HttpRequest.newBuilder(URI.create(base+"/health/ready")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(2,scalar("SELECT count(*) FROM fs.file_version WHERE state='PENDING'"));
                var verifyWorker=new UploadVerificationWorker(work,resolver,new TransferLimits(1024*1024,1,1024,Duration.ofSeconds(5)),CLOCK);
                assertEquals(UploadVerificationWorker.Outcome.SCAN_QUEUED,verifyWorker.runOne());
                assertEquals(1,scalar("SELECT count(*) FROM fs.upload_integrity_verification WHERE verified_at IS NOT NULL AND observed_size=32768"));
                assertEquals(2,opened.get());
                assertEquals(UploadScanWorker.Outcome.COMPLETED,worker(scanRepo,resolver,scanner(Scan.CLEAN,"application/octet-stream"),CLOCK).runOne());
                var available=http.send(metadata,HttpResponse.BodyHandlers.ofString());
                assertEquals(200,available.statusCode());OpenApiContract.assertResponse(available);
                assertEquals("AVAILABLE",tools.jackson.databind.json.JsonMapper.builder().build().readTree(available.body()).get("versionState").asText());
                var terminal=http.send(command(commandBase+"/complete","user",completionBody),HttpResponse.BodyHandlers.ofString());
                assertEquals(200,terminal.statusCode(),terminal.body());OpenApiContract.assertResponse(terminal);
                var terminalJson=tools.jackson.databind.json.JsonMapper.builder().build().readTree(terminal.body());
                assertEquals("COMPLETED",terminalJson.get("uploadState").asText());
                assertEquals("AVAILABLE",terminalJson.get("versionState").asText());
                assertEquals("SUCCEEDED",terminalJson.get("status").asText());
                String downloadCommand=base+"/v1/files/"+file+"/versions/"+json.get("versionId").asText()+"/download-handle";
                assertEquals(404,http.send(command(downloadCommand,"user","{}"),HttpResponse.BodyHandlers.ofString()).statusCode());
                grant("fixture-user","asset","DOWNLOAD_TRANSFER","OWNED_FILE",null);
                var issuedDownload=http.send(command(downloadCommand,"user","{}"),HttpResponse.BodyHandlers.ofString());
                assertEquals(200,issuedDownload.statusCode(),issuedDownload.body());OpenApiContract.assertResponse(issuedDownload);
                String downloadHandle=tools.jackson.databind.json.JsonMapper.builder().build().readTree(issuedDownload.body()).get("handle").asText();
                var downloadRequest=downloadRequest(base+"/transfer/v1/download","user",downloadHandle);
                var downloaded=http.send(downloadRequest,HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200,downloaded.statusCode());assertArrayEquals(payload,downloaded.body());
                assertEquals("no-store",downloaded.headers().firstValue("Cache-Control").orElseThrow());
                assertTrue(downloaded.headers().firstValue("Location").isEmpty());
                assertEquals(403,http.send(downloadRequest,HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(409,http.send(command(commandBase+"/complete","user","{\"sha256\":\""+"0".repeat(64)+"\"}"),HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(4,closed.get());assertEquals(4,adapterCloses.get());
                assertEquals(1,scalar("SELECT count(*) FROM fs.upload_transfer_handle WHERE claimed_at IS NOT NULL"));
                for(String action:List.of("DELETE_FILE","RESTORE_FILE","LIST_DELETED_METADATA"))
                    grant("fixture-user","asset",action,"OWNED_FILE",null);
                var staleIssued=http.send(command(downloadCommand,"user","{}"),HttpResponse.BodyHandlers.ofString());
                assertEquals(200,staleIssued.statusCode());OpenApiContract.assertResponse(staleIssued);
                var stale=tools.jackson.databind.json.JsonMapper.builder().build().readTree(staleIssued.body()).get("handle").asText();
                var staleRequest=downloadRequest(base+"/transfer/v1/download","user",stale);
                var deleted=http.send(lifecycleRequest(base+"/v1/files/"+file+"/delete?expectedRevision=0"),HttpResponse.BodyHandlers.ofString());
                assertEquals(200,deleted.statusCode(),deleted.body());OpenApiContract.assertResponse(deleted);
                assertEquals(404,http.send(metadata,HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(404,http.send(command(downloadCommand,"user","{}"),HttpResponse.BodyHandlers.ofString()).statusCode());
                var denied=http.send(staleRequest,HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(403,denied.statusCode());assertFalse(Arrays.equals(payload,denied.body()));
                assertEquals(4,opened.get(),"deleted bytes must not reach provider");
                var trash=catalogGet(http,base,"/v1/deleted-files","user",TENANT);
                assertEquals(200,trash.statusCode());OpenApiContract.assertResponse(trash);
                assertTrue(trash.body().contains(file));
                var restored=http.send(lifecycleRequest(base+"/v1/files/"+file+"/restore?expectedRevision=1"),HttpResponse.BodyHandlers.ofString());
                assertEquals(200,restored.statusCode());OpenApiContract.assertResponse(restored);
                assertEquals(403,http.send(staleRequest,HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(4,opened.get(),"restore must not resurrect old handle");
                var freshIssued=http.send(command(downloadCommand,"user","{}"),HttpResponse.BodyHandlers.ofString());
                assertEquals(200,freshIssued.statusCode());OpenApiContract.assertResponse(freshIssued);
                var fresh=tools.jackson.databind.json.JsonMapper.builder().build().readTree(freshIssued.body()).get("handle").asText();
                var freshBytes=http.send(downloadRequest(base+"/transfer/v1/download","user",fresh),HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200,freshBytes.statusCode());assertArrayEquals(payload,freshBytes.body());
                assertEquals(5,closed.get());assertEquals(5,adapterCloses.get());
                assertArrayEquals(payload,fixture.bytes("fixture-oss",key));
            }
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private UploadIntegrityVerification verification(TransferProviderResolver resolver) {
        return new UploadIntegrityVerification(secured(CLOCK),
                new JdbcUploadVerification(new JdbcUploadTransfers(source,CLOCK,Duration.ofMinutes(1),5)),
                resolver,new TransferLimits(1024*1024,2,1024,Duration.ofSeconds(15)),CLOCK);
    }

    /** Controlled provider outcomes for negative paths; real S3/OSS reads are exercised above. */
    private static final class IntegrityFixture implements TransferProviderResolver {
        volatile byte[] bytes;
        volatile Long headSize;
        volatile boolean failClose;
        final AtomicInteger opens=new AtomicInteger(),closes=new AtomicInteger(),streams=new AtomicInteger();
        @Override public Lease resolve(TransferInstruction instruction) {
            opens.incrementAndGet();
            return new Lease() {
                @Override public StorageProvider provider() {
                    return new StorageProvider() {
                        @Override public Set<Capability> capabilities(){return Set.of();}
                        @Override public Metadata head(Locator key,Call call){
                            if(bytes==null)throw new StorageFailure(ErrorCode.NOT_FOUND);
                            return new Metadata(headSize==null?bytes.length:headSize,null,"application/octet-stream");
                        }
                        @Override public java.io.InputStream get(Locator key,Call call){
                            return new java.io.ByteArrayInputStream(bytes){
                                @Override public void close(){streams.incrementAndGet();}
                            };
                        }
                        @Override public Metadata put(Locator key,java.io.InputStream body,long size,Call call){throw new AssertionError("verification cannot write");}
                        @Override public void delete(Locator key,Call call){throw new AssertionError("verification cannot delete");}
                        @Override public Page list(LocationId location,String prefix,String cursor,int limit,Call call){throw new AssertionError("verification cannot list");}
                    };
                }
                @Override public void close(){closes.incrementAndGet();if(failClose)throw new TransferFailure(TransferFailure.Code.UNAVAILABLE);}
            };
        }
    }

    @Test void verificationRequiresClaimAndRetriesMissingObjectWithoutReissuingUpload() throws Exception {
        allow();var issued=issue();var fixture=new IntegrityFixture();var verifier=verification(fixture);
        byte[] bytes="hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);String digest=sha256(bytes);
        assertThrows(RuntimeException.class,()->verifier.verify("user",TENANT,view.session(),digest,null));
        assertEquals(0,fixture.opens.get());
        assertThrows(SQLException.class,()->sql("INSERT INTO fs.upload_integrity_verification(session_id,expected_sha256,requested_at) VALUES (?,?,?)",
                view.session().value(),digest,NOW.atOffset(ZoneOffset.UTC)));
        authority().claim("user",issued.handle(),TransferInstruction.Action.UPLOAD);
        assertThrows(SQLException.class,()->sql("""
                INSERT INTO fs.upload_integrity_verification(session_id,expected_sha256,requested_at,verified_at,observed_size,observed_sha256)
                VALUES (?,?,?,?,?,?)
                """,view.session().value(),digest,NOW.atOffset(ZoneOffset.UTC),NOW.atOffset(ZoneOffset.UTC),5,digest));
        assertEquals(TransferFailure.Code.NOT_FOUND,assertThrows(TransferFailure.class,()->verifier.verify("user",TENANT,view.session(),digest,null)).code());
        assertEquals(1,scalar("SELECT count(*) FROM fs.upload_integrity_verification WHERE verified_at IS NULL"));
        assertThrows(SQLException.class,()->sql("UPDATE fs.upload_integrity_verification SET verified_at=?,observed_size=6,observed_sha256=expected_sha256",NOW.atOffset(ZoneOffset.UTC)));
        assertThrows(Failure.class,()->verifier.verify("user",TENANT,view.session(),"0".repeat(64),null));
        assertThrows(RuntimeException.class,()->verifier.verify("other",TENANT,view.session(),digest,null));
        fixture.bytes=bytes;
        assertTrue(verifier.verify("user",TENANT,view.session(),digest,null).integrityMatched());
        assertTrue(verification(fixture).verify("user",TENANT,view.session(),digest,null).integrityMatched());
        assertEquals(2,fixture.opens.get());assertEquals(2,fixture.closes.get());assertEquals(1,fixture.streams.get());
        assertEquals(1,scalar("SELECT count(*) FROM fs_kernel.platform_event_outbox WHERE event_type='file-storage.transfer.upload.integrity-matched'"));
        assertEquals(1,scalar("SELECT count(*) FROM fs.file_version WHERE state='PENDING'"));
        assertThrows(SQLException.class,()->sql("UPDATE fs.upload_integrity_verification SET verified_at=NULL,observed_size=NULL,observed_sha256=NULL"));
        sql("UPDATE fs.file_grant SET revoked=true WHERE action='UPLOAD_TRANSFER'");
        assertThrows(RuntimeException.class,()->verifier.verify("user",TENANT,view.session(),digest,null));
        assertEquals(2,fixture.opens.get());
    }

    @Test void mismatchedBytesAndFailedIntentNeverRecordMatchingEvidence() throws Exception {
        allow();var issued=issue();authority().claim("user",issued.handle(),TransferInstruction.Action.UPLOAD);
        var fixture=new IntegrityFixture();var verifier=verification(fixture);
        byte[] expected="hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);String digest=sha256(expected);
        fixture.bytes="wrong".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(TransferFailure.Code.INVALID,assertThrows(TransferFailure.class,()->verifier.verify("user",TENANT,view.session(),digest,null)).code());
        fixture.bytes=new byte[4];fixture.headSize=5L;
        assertEquals(TransferFailure.Code.INVALID,assertThrows(TransferFailure.class,()->verifier.verify("user",TENANT,view.session(),digest,null)).code());
        fixture.bytes=new byte[6];
        assertEquals(TransferFailure.Code.INVALID,assertThrows(TransferFailure.class,()->verifier.verify("user",TENANT,view.session(),digest,null)).code());
        fixture.bytes=expected;fixture.headSize=null;fixture.failClose=true;
        assertEquals(TransferFailure.Code.UNAVAILABLE,assertThrows(TransferFailure.class,()->verifier.verify("user",TENANT,view.session(),digest,null)).code());
        fixture.failClose=false;
        sql("ALTER TABLE fs_kernel.platform_event_outbox ADD CONSTRAINT reject_integrity_test CHECK (event_type <> 'file-storage.transfer.upload.integrity-matched') NOT VALID");
        try {
            assertThrows(RuntimeException.class,()->verifier.verify("user",TENANT,view.session(),digest,null));
            assertEquals(0,scalar("SELECT count(*) FROM fs.upload_integrity_verification WHERE verified_at IS NOT NULL"));
            assertEquals(0,scalar("SELECT count(*) FROM fs_kernel.platform_audit_event WHERE action='file-storage.transfer.upload.integrity-matched'"));
        } finally {sql("ALTER TABLE fs_kernel.platform_event_outbox DROP CONSTRAINT reject_integrity_test");}
        assertTrue(verifier.verify("user",TENANT,view.session(),digest,null).integrityMatched());
        assertEquals(6,fixture.opens.get());assertEquals(6,fixture.closes.get());assertEquals(6,fixture.streams.get());
    }

    private JdbcUploadScan scans(Clock clock,int attempts) {
        return new JdbcUploadScan(new JdbcUploadTransfers(source,clock,Duration.ofMinutes(1),5),
                new JdbcOperations(source,clock,Duration.ofSeconds(10),Duration.ZERO,4),ADMIN,attempts);
    }

    private UploadScanWorker worker(JdbcUploadScan scans,TransferProviderResolver resolver,ContentScanner scanner,Clock clock){
        return new UploadScanWorker(scans,resolver,scanner,new TransferLimits(1024*1024,1,1024,Duration.ofSeconds(5)),clock);
    }

    private static ContentScanner scanner(Scan verdict,String mime) {
        return (stream,size,deadline)->{
            try {
                byte[] buffer=new byte[1024];
                while(stream.read(buffer)!=-1) {}
                return new ContentScanner.Verdict(verdict,mime);
            } catch(java.io.IOException ex){throw new IllegalStateException("fixture scanner");}
        };
    }

    private IntegrityFixture matched() throws Exception {
        allow();var issued=issue();authority().claim("user",issued.handle(),TransferInstruction.Action.UPLOAD);
        var fixture=new IntegrityFixture();fixture.bytes="hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(verification(fixture).verify("user",TENANT,view.session(),sha256(fixture.bytes),null).integrityMatched());
        return fixture;
    }

    @Test void scanQueueAndCleanCommitAreAtomicAndRehydrateCurrentState() throws Exception {
        var scans=scans(CLOCK,3);var completion=new UploadCompletion(secured,scans);
        assertThrows(RuntimeException.class,()->completion.begin("user",TENANT,view.session(),null));
        var fixture=matched();
        assertThrows(SQLException.class,()->sql("UPDATE fs.file_version SET state='AVAILABLE'"));
        sql("ALTER TABLE fs_kernel.platform_event_outbox ADD CONSTRAINT reject_scan_queue CHECK (event_type <> 'file-storage.transfer.upload.scan-queued') NOT VALID");
        try {
            assertThrows(RuntimeException.class,()->completion.begin("user",TENANT,view.session(),null));
            assertEquals(0,scalar("SELECT count(*) FROM fs.storage_operation WHERE kind='SCAN_UPLOAD'"));
            assertEquals(1,scalar("SELECT count(*) FROM fs.file_version WHERE state='PENDING'"));
        } finally{sql("ALTER TABLE fs_kernel.platform_event_outbox DROP CONSTRAINT reject_scan_queue");}
        UUID job=completion.begin("user",TENANT,view.session(),null);
        assertEquals(job,completion.begin("user",TENANT,view.session(),null));
        assertThrows(RuntimeException.class,()->completion.begin("other",TENANT,view.session(),null));
        assertEquals(VersionState.VERIFYING,secured.session("user",TENANT,null,view.session()).value().versionState());
        assertThrows(SQLException.class,()->sql("UPDATE fs.file_version SET state='AVAILABLE'"));
        var claimed=scans.claim(1).claims().getFirst();
        var snapshot=scans.load(claimed);
        sql("ALTER TABLE fs_kernel.platform_event_outbox ADD CONSTRAINT reject_scan_finish CHECK (event_type <> 'file-storage.upload.completed') NOT VALID");
        try{
            assertThrows(RuntimeException.class,()->scans.finish(snapshot,new Content(5,sha256(fixture.bytes),"text/plain"),Scan.CLEAN));
            assertEquals(0,scalar("SELECT count(*) FROM fs.upload_scan_evidence"));
            assertEquals(1,scalar("SELECT count(*) FROM fs.file_version WHERE state='VERIFYING'"));
            assertEquals(1,scalar("SELECT count(*) FROM fs.storage_operation WHERE kind='SCAN_UPLOAD' AND status='PENDING'"));
        }finally{sql("ALTER TABLE fs_kernel.platform_event_outbox DROP CONSTRAINT reject_scan_finish");}
        assertTrue(scans.retry(claimed));
        assertEquals(UploadScanWorker.Outcome.COMPLETED,worker(scans,fixture,scanner(Scan.CLEAN,"text/plain"),CLOCK).runOne());
        var result=secured.session("user",TENANT,null,view.session()).value();
        assertEquals(VersionState.AVAILABLE,result.versionState());assertEquals(UploadState.COMPLETED,result.uploadState());
        assertEquals(1,scalar("SELECT count(*) FROM fs.upload_scan_evidence WHERE verdict='CLEAN'"));
        assertEquals(1,scalar("SELECT count(*) FROM fs_kernel.platform_event_outbox WHERE event_type='file-storage.upload.completed'"));
        assertThrows(SQLException.class,()->sql("UPDATE fs.upload_scan_evidence SET verdict='MALICIOUS'"));
        assertThrows(RuntimeException.class,()->scans.finish(snapshot,new Content(5,sha256(fixture.bytes),"text/plain"),Scan.CLEAN));
        assertEquals(UploadScanWorker.Outcome.IDLE,worker(scans,fixture,scanner(Scan.CLEAN,"text/plain"),CLOCK).runOne());
    }

    @Test void maliciousScanQuarantinesAndNeverAvailable() throws Exception {
        var fixture=matched();var scans=scans(CLOCK,3);
        new UploadCompletion(secured,scans).begin("user",TENANT,view.session(),null);
        assertEquals(UploadScanWorker.Outcome.QUARANTINED,worker(scans,fixture,scanner(Scan.MALICIOUS,"application/octet-stream"),CLOCK).runOne());
        var result=secured.session("user",TENANT,null,view.session()).value();
        assertEquals(VersionState.QUARANTINED,result.versionState());assertEquals(UploadState.FAILED,result.uploadState());
        assertEquals(1,scalar("SELECT count(*) FROM fs.upload_scan_evidence WHERE verdict='MALICIOUS'"));
        assertEquals(0,scalar("SELECT count(*) FROM fs.file_version WHERE state='AVAILABLE'"));
        assertThrows(SQLException.class,()->sql("UPDATE fs.file_version SET state='AVAILABLE'"));
    }

    @Test void scanOutageRetryAndExpiredClaimCannotCommit() throws Exception {
        var fixture=matched();var scans=scans(CLOCK,3);
        new UploadCompletion(secured,scans).begin("user",TENANT,view.session(),null);
        assertEquals(UploadScanWorker.Outcome.RETRY,worker(scans,fixture,(stream,size,deadline)->new ContentScanner.Verdict(Scan.UNAVAILABLE,null),CLOCK).runOne());
        assertEquals(0,scalar("SELECT count(*) FROM fs.upload_scan_evidence"));
        var stale=scans.claim(1).claims().getFirst();var old=scans.load(stale);
        Clock later=Clock.fixed(NOW.plusSeconds(11),ZoneOffset.UTC);var replacement=scans(later,3);
        var current=replacement.claim(1).claims().getFirst();
        assertNotEquals(stale.token(),current.token());
        assertThrows(RuntimeException.class,()->replacement.finish(old,new Content(5,sha256(fixture.bytes),"text/plain"),Scan.CLEAN));
        assertEquals(0,scalar("SELECT count(*) FROM fs.upload_scan_evidence"));
        // Last attempt crashes. Expired claim is dead-lettered and explicitly reconciled.
        Clock after=Clock.fixed(NOW.plusSeconds(22),ZoneOffset.UTC);var recovery=scans(after,3);
        assertEquals(1,recovery.claim(1).exhausted());
        assertTrue(recovery.reconcileDead(TENANT,view.session()));
        assertFalse(recovery.reconcileDead(TENANT,view.session()));
        assertEquals(VersionState.FAILED,secured.session("user",TENANT,null,view.session()).value().versionState());
        assertEquals(0,scalar("SELECT count(*) FROM fs.upload_scan_evidence"));
    }

    @Test void changedBytesAndPrematureCleanCannotPassScanGate() throws Exception {
        var fixture=matched();var scans=scans(CLOCK,3);
        new UploadCompletion(secured,scans).begin("user",TENANT,view.session(),null);
        fixture.bytes="wrong".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(UploadScanWorker.Outcome.FAILED,worker(scans,fixture,scanner(Scan.CLEAN,"text/plain"),CLOCK).runOne());
        assertEquals(0,scalar("SELECT count(*) FROM fs.upload_scan_evidence"));
        assertEquals(VersionState.FAILED,secured.session("user",TENANT,null,view.session()).value().versionState());
    }

    @Test void cleanWithoutFullConsumptionFailsClosed() throws Exception {
        var fixture=matched();var scans=scans(CLOCK,3);
        new UploadCompletion(secured,scans).begin("user",TENANT,view.session(),null);
        assertEquals(UploadScanWorker.Outcome.FAILED,worker(scans,fixture,(stream,size,deadline)->new ContentScanner.Verdict(Scan.CLEAN,"text/plain"),CLOCK).runOne());
        assertEquals(0,scalar("SELECT count(*) FROM fs.upload_scan_evidence"));
        assertEquals(VersionState.FAILED,secured.session("user",TENANT,null,view.session()).value().versionState());
    }

    @Test void expiredSessionNeverScansOrBecomesAvailable() throws Exception {
        var fixture=matched();var scans=scans(CLOCK,3);
        new UploadCompletion(secured,scans).begin("user",TENANT,view.session(),null);
        Clock expired=Clock.fixed(NOW.plusSeconds(301),ZoneOffset.UTC);
        var recovery=scans(expired,3);
        assertEquals(UploadScanWorker.Outcome.FAILED,worker(recovery,fixture,scanner(Scan.CLEAN,"text/plain"),expired).runOne());
        assertEquals(1,fixture.opens.get(),"only original integrity read");
        var result=secured(expired).session("user",TENANT,null,view.session()).value();
        assertEquals(VersionState.FAILED,result.versionState());assertEquals(UploadState.EXPIRED,result.uploadState());
        assertEquals(0,scalar("SELECT count(*) FROM fs.upload_scan_evidence"));
    }

    @Test void cleanMimeMismatchFailsAndRevokedGrantCannotCommit() throws Exception {
        var fixture=matched();var scans=scans(CLOCK,3);
        new UploadCompletion(secured,scans).begin("user",TENANT,view.session(),null);
        var claim=scans.claim(1).claims().getFirst();var snapshot=scans.load(claim);
        sql("UPDATE fs.file_grant SET revoked=true WHERE action='UPLOAD_TRANSFER'");
        assertThrows(Failure.class,()->scans.finish(snapshot,new Content(5,sha256(fixture.bytes),"text/plain"),Scan.CLEAN));
        assertEquals(0,scalar("SELECT count(*) FROM fs.upload_scan_evidence"));
        sql("UPDATE fs.file_grant SET revoked=false WHERE action='UPLOAD_TRANSFER'");
        assertTrue(scans.retry(claim));
        assertEquals(UploadScanWorker.Outcome.FAILED,worker(scans,fixture,scanner(Scan.CLEAN,"application/octet-stream"),CLOCK).runOne());
        assertEquals(VersionState.FAILED,secured.session("user",TENANT,null,view.session()).value().versionState());
    }

    @Test void scanCannotFollowDriftedPhysicalPin() throws Exception {
        var fixture=matched();var scans=scans(CLOCK,3);
        String original=objectKey();
        sql("UPDATE fs.file_version SET object_key='private-prefix/drifted'");
        assertThrows(Failure.class,()->new UploadCompletion(secured,scans).begin("user",TENANT,view.session(),null));
        sql("UPDATE fs.file_version SET object_key=?",original);
        new UploadCompletion(secured,scans).begin("user",TENANT,view.session(),null);
        sql("UPDATE fs.file_version SET object_key='private-prefix/drifted'");
        var claim=scans.claim(1).claims().getFirst();
        assertThrows(Failure.class,()->scans.load(claim));
        assertEquals(1,fixture.opens.get());
        assertEquals(0,scalar("SELECT count(*) FROM fs.upload_scan_evidence"));
    }

    private static HttpRequest command(String uri,String proof,String body){
        return HttpRequest.newBuilder(URI.create(uri)).header("Authorization","Bearer "+proof)
            .header("X-Tenant-Id",TENANT.value().toString()).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private JdbcCompletionWork completionWork(Clock clock,JdbcUploadScan scans){
        return new JdbcCompletionWork(new JdbcUploadTransfers(source,clock,Duration.ofMinutes(1),5),
            new JdbcOperations(source,clock,Duration.ofSeconds(10),Duration.ZERO,4),scans,3);
    }

    @Test void verificationQueueRollbackAndStaleLeaseCannotHandoff() throws Exception {
        allow();var issued=issue();authority().claim("user",issued.handle(),TransferInstruction.Action.UPLOAD);
        var scans=scans(CLOCK,3);var work=completionWork(CLOCK,scans);
        var commands=new CompletionCommands(secured,new JdbcUploadTransfers(source,CLOCK,Duration.ofMinutes(1),5),work);
        byte[] bytes="hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);String digest=sha256(bytes);
        sql("ALTER TABLE fs_kernel.platform_event_outbox ADD CONSTRAINT reject_verify_request CHECK (event_type <> 'file-storage.transfer.upload.verification-requested') NOT VALID");
        try {
            assertThrows(RuntimeException.class,()->commands.complete("user",TENANT,view.session(),digest,null));
            assertEquals(0,scalar("SELECT count(*) FROM fs.upload_integrity_verification"));
            assertEquals(0,scalar("SELECT count(*) FROM fs.storage_operation"));
        }finally{sql("ALTER TABLE fs_kernel.platform_event_outbox DROP CONSTRAINT reject_verify_request");}
        var accepted=commands.complete("user",TENANT,view.session(),digest,null).value();
        assertEquals(accepted,commands.complete("user",TENANT,view.session(),digest,null).value());
        assertThrows(RuntimeException.class,()->commands.complete("other",TENANT,view.session(),digest,null));
        var stale=work.claim().claims().getFirst();var old=work.load(stale);
        Clock later=Clock.fixed(NOW.plusSeconds(11),ZoneOffset.UTC);var replacement=completionWork(later,scans(later,3));
        var claim=replacement.claim().claims().getFirst();assertNotEquals(stale.token(),claim.token());
        assertThrows(RuntimeException.class,()->replacement.finish(old,digest));
        var current=replacement.load(claim);
        sql("ALTER TABLE fs_kernel.platform_event_outbox ADD CONSTRAINT reject_verify_handoff CHECK (event_type <> 'file-storage.transfer.upload.scan-queued') NOT VALID");
        try{
            assertThrows(RuntimeException.class,()->replacement.finish(current,digest));
            assertEquals(0,scalar("SELECT count(*) FROM fs.upload_integrity_verification WHERE verified_at IS NOT NULL"));
            assertEquals(0,scalar("SELECT count(*) FROM fs.storage_operation WHERE kind='SCAN_UPLOAD'"));
            assertEquals(1,scalar("SELECT count(*) FROM fs.file_version WHERE state='PENDING'"));
        }finally{sql("ALTER TABLE fs_kernel.platform_event_outbox DROP CONSTRAINT reject_verify_handoff");}
        replacement.finish(current,digest);
        assertEquals(1,scalar("SELECT count(*) FROM fs.storage_operation WHERE kind='VERIFY_UPLOAD' AND status='SUCCEEDED'"));
        assertEquals(1,scalar("SELECT count(*) FROM fs.storage_operation WHERE kind='SCAN_UPLOAD' AND status='PENDING'"));
        assertEquals(VersionState.VERIFYING,secured.session("user",TENANT,null,view.session()).value().versionState());
    }

    @Test void verificationWorkerRetriesMissingBytesAndNeverStoresProof() throws Exception {
        allow();var issued=issue();authority().claim("user",issued.handle(),TransferInstruction.Action.UPLOAD);
        var scans=scans(CLOCK,3);var work=completionWork(CLOCK,scans);
        byte[] bytes="hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);String digest=sha256(bytes);
        var commands=new CompletionCommands(secured,new JdbcUploadTransfers(source,CLOCK,Duration.ofMinutes(1),5),work);
        commands.complete("user",TENANT,view.session(),digest,null);
        var fixture=new IntegrityFixture();
        var worker=new UploadVerificationWorker(work,fixture,new TransferLimits(1024,1,1024,Duration.ofSeconds(5)),CLOCK);
        assertEquals(UploadVerificationWorker.Outcome.RETRY,worker.runOne());
        assertEquals(0,scalar("SELECT count(*) FROM fs.upload_integrity_verification WHERE verified_at IS NOT NULL"));
        fixture.bytes=bytes;
        assertEquals(UploadVerificationWorker.Outcome.SCAN_QUEUED,worker.runOne());
        assertEquals("SUCCEEDED",commands.complete("user",TENANT,view.session(),digest,null).value().status());
        assertEquals(UploadScanWorker.Outcome.COMPLETED,worker(scans,fixture,scanner(Scan.CLEAN,"text/plain"),CLOCK).runOne());
        assertEquals(VersionState.AVAILABLE,secured.session("user",TENANT,null,view.session()).value().versionState());
        assertEquals(3,fixture.opens.get());assertEquals(3,fixture.closes.get());
    }

    @Test void explicitWorkerRuntimeSchedulesDurableVerificationAndScan() throws Exception {
        allow();var issued=issue();authority().claim("user",issued.handle(),TransferInstruction.Action.UPLOAD);
        var scans=scans(CLOCK,3);var work=completionWork(CLOCK,scans);
        var fixture=new IntegrityFixture();fixture.bytes="hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var commands=new CompletionCommands(secured,new JdbcUploadTransfers(source,CLOCK,Duration.ofMinutes(1),5),work);
        commands.complete("user",TENANT,view.session(),sha256(fixture.bytes),null);
        var verify=new UploadVerificationWorker(work,fixture,new TransferLimits(1024,1,1024,Duration.ofSeconds(5)),CLOCK);
        var scan=worker(scans,fixture,scanner(Scan.CLEAN,"text/plain"),CLOCK);
        var app=RuntimeApplication.application(UploadWorkerConfiguration.class);
        app.addInitializers(ctx->{
            ctx.getBeanFactory().registerSingleton("uploadVerificationWorker",verify);
            ctx.getBeanFactory().registerSingleton("uploadScanWorker",scan);
            ctx.getBeanFactory().registerSingleton("uploadRecovery",recovery(CLOCK));
        });
        try(var ctx=app.run("--server.port=0","--fs.role=WORKER","--fs.upload-worker.delay-ms=25")){
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while(scalar("SELECT count(*) FROM fs.file_version WHERE state='AVAILABLE'")==0&&System.nanoTime()<deadline)
                Thread.sleep(25);
            assertEquals(1,scalar("SELECT count(*) FROM fs.file_version WHERE state='AVAILABLE'"));
            assertEquals(2,scalar("SELECT count(*) FROM fs.storage_operation WHERE status='SUCCEEDED'"));
            assertEquals(2,fixture.opens.get());assertEquals(2,fixture.closes.get());
        }
        var wrong=RuntimeApplication.application(UploadWorkerConfiguration.class);
        wrong.addInitializers(ctx->{
            ctx.getBeanFactory().registerSingleton("uploadVerificationWorker",verify);
            ctx.getBeanFactory().registerSingleton("uploadScanWorker",scan);
            ctx.getBeanFactory().registerSingleton("uploadRecovery",recovery(CLOCK));
        });
        assertThrows(RuntimeException.class,()->{try(var ctx=wrong.run("--server.port=0","--fs.role=API","--fs.upload-worker.delay-ms=25")){
            fail("API must not run upload workers");
        }});
    }

    private JdbcUploadRecovery recovery(Clock clock){
        return new JdbcUploadRecovery(new JdbcUploadTransfers(source,clock,Duration.ofMinutes(1),5),ADMIN);
    }

    @Test void expiredUnclaimedAndClaimedSessionsTerminalizeWithoutDeletingOrReissuing() throws Exception {
        allow();var issued=issue();
        var other=secured.initiate("user",TENANT,null,new Initiate("attachment","other.txt",5,"text/plain","other-key")).value();
        var otherHandle=authority().issue("user",TENANT,other.session(),null);
        authority().claim("user",otherHandle.handle(),TransferInstruction.Action.UPLOAD);
        var future=Clock.fixed(NOW.plusSeconds(301),ZoneOffset.UTC);
        var recovery=recovery(future);
        assertTrue(recovery.runOne());assertTrue(recovery.runOne());assertFalse(recovery.runOne());
        assertEquals(2,scalar("SELECT count(*) FROM fs.upload_session WHERE state='EXPIRED'"));
        assertEquals(2,scalar("SELECT count(*) FROM fs.file_version WHERE state='FAILED'"));
        assertEquals(2,scalar("SELECT count(*) FROM fs.upload_terminal_recovery WHERE reason='SESSION_EXPIRED' AND cleanup_disposition='HOLD'"));
        assertEquals(2,scalar("SELECT count(*) FROM fs.upload_transfer_handle"));
        assertEquals(0,scalar("SELECT count(*) FROM fs.storage_operation WHERE kind='CLEANUP_UPLOAD'"));
        assertEquals(2,scalar("SELECT count(*) FROM fs_kernel.platform_event_outbox WHERE event_type='file-storage.upload.recovered'"));
        deny(authority(future),"user",issued.handle());
        assertThrows(RuntimeException.class,()->authority(future).issue("user",TENANT,other.session(),null));
        assertEquals(UploadState.EXPIRED,secured(future).session("user",TENANT,null,view.session()).value().uploadState());
        assertThrows(SQLException.class,()->sql("UPDATE fs.upload_terminal_recovery SET cleanup_disposition='DELETE'"));
    }

    @Test void deadVerificationRecoveryIsAtomicAndTerminalReplayIsReadOnly() throws Exception {
        allow();var issued=issue();authority().claim("user",issued.handle(),TransferInstruction.Action.UPLOAD);
        var scans=scans(CLOCK,3);var work=completionWork(CLOCK,scans);
        var commands=new CompletionCommands(secured,new JdbcUploadTransfers(source,CLOCK,Duration.ofMinutes(1),5),work);
        String digest=sha256("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var accepted=commands.complete("user",TENANT,view.session(),digest,null).value();
        var claim=work.claim().claims().getFirst();
        assertTrue(work.retry(claim,true));
        assertThrows(SQLException.class,()->sql("UPDATE fs.file_version SET state='FAILED'"));
        sql("ALTER TABLE fs_kernel.platform_event_outbox ADD CONSTRAINT reject_recovery CHECK (event_type <> 'file-storage.upload.recovered') NOT VALID");
        try{
            assertThrows(RuntimeException.class,()->recovery(CLOCK).runOne());
            assertEquals(0,scalar("SELECT count(*) FROM fs.upload_terminal_recovery"));
            assertEquals(1,scalar("SELECT count(*) FROM fs.file_version WHERE state='PENDING'"));
        }finally{sql("ALTER TABLE fs_kernel.platform_event_outbox DROP CONSTRAINT reject_recovery");}
        assertTrue(recovery(CLOCK).runOne());assertFalse(recovery(CLOCK).runOne());
        var terminal=commands.complete("user",TENANT,view.session(),digest,null).value();
        assertEquals(accepted.operation(),terminal.operation());assertEquals("DEAD",terminal.status());
        assertEquals(UploadState.FAILED,terminal.uploadState());assertEquals(VersionState.FAILED,terminal.versionState());
        assertEquals(terminal,commands.complete("user",TENANT,view.session(),digest,null).value());
        assertThrows(RuntimeException.class,()->commands.complete("other",TENANT,view.session(),digest,null));
        assertThrows(RuntimeException.class,()->commands.complete("user",TENANT,view.session(),"0".repeat(64),null));
        assertEquals(1,scalar("SELECT count(*) FROM fs.storage_operation"));
        assertEquals(1,scalar("SELECT count(*) FROM fs_kernel.platform_event_outbox WHERE event_type='file-storage.upload.recovered'"));
        sql("UPDATE fs.file_grant SET revoked=true WHERE action='UPLOAD_TRANSFER'");
        assertThrows(RuntimeException.class,()->commands.complete("user",TENANT,view.session(),digest,null));
    }

    @Test void expiredVerificationFencesClaimAndConcurrentRecoveryHasOneWinner() throws Exception {
        allow();var issued=issue();authority().claim("user",issued.handle(),TransferInstruction.Action.UPLOAD);
        var work=completionWork(CLOCK,scans(CLOCK,3));var commands=new CompletionCommands(secured,new JdbcUploadTransfers(source,CLOCK,Duration.ofMinutes(1),5),work);
        String digest=sha256("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        commands.complete("user",TENANT,view.session(),digest,null);
        var claim=work.claim().claims().getFirst();var snapshot=work.load(claim);
        Clock future=Clock.fixed(NOW.plusSeconds(301),ZoneOffset.UTC);
        var start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            Callable<Boolean> attempt=()->{start.await();return recovery(future).runOne();};
            var a=executor.submit(attempt);var b=executor.submit(attempt);start.countDown();
            assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
        assertThrows(RuntimeException.class,()->work.finish(snapshot,digest));
        assertEquals(0,scalar("SELECT count(*) FROM fs.upload_integrity_verification WHERE verified_at IS NOT NULL"));
        assertEquals(1,scalar("SELECT count(*) FROM fs.storage_operation WHERE status='DEAD' AND claim_token IS NULL"));
        var later=new CompletionCommands(secured(future),new JdbcUploadTransfers(source,future,Duration.ofMinutes(1),5),completionWork(future,scans(future,3)));
        var terminal=later.complete("user",TENANT,view.session(),digest,null).value();
        assertEquals(UploadState.EXPIRED,terminal.uploadState());assertEquals(VersionState.FAILED,terminal.versionState());
        assertEquals(1,scalar("SELECT count(*) FROM fs.upload_terminal_recovery"));
    }

    @Test void expiredScanCannotPromoteAndRecoveryKeepsIntegrityEvidence() throws Exception {
        var fixture=matched();var scans=scans(CLOCK,3);
        new UploadCompletion(secured,scans).begin("user",TENANT,view.session(),null);
        var claim=scans.claim(1).claims().getFirst();var snapshot=scans.load(claim);
        Clock future=Clock.fixed(NOW.plusSeconds(301),ZoneOffset.UTC);
        assertTrue(recovery(future).runOne());
        assertThrows(RuntimeException.class,()->scans.finish(snapshot,new Content(5,sha256(fixture.bytes),"text/plain"),Scan.CLEAN));
        assertEquals(1,scalar("SELECT count(*) FROM fs.upload_integrity_verification WHERE verified_at IS NOT NULL"));
        assertEquals(0,scalar("SELECT count(*) FROM fs.upload_scan_evidence"));
        assertEquals(1,scalar("SELECT count(*) FROM fs.upload_session WHERE state='EXPIRED'"));
        assertEquals(1,scalar("SELECT count(*) FROM fs.storage_operation WHERE kind='SCAN_UPLOAD' AND status='DEAD'"));
        assertEquals(1,scalar("SELECT count(*) FROM fs.upload_terminal_recovery WHERE cleanup_disposition='HOLD'"));
    }

    @Test void abandonedScanLastLeaseIsReconciledWithoutPhysicalCleanup() throws Exception {
        var fixture=matched();var scans=scans(CLOCK,1);
        new UploadCompletion(secured,scans).begin("user",TENANT,view.session(),null);
        var claim=scans.claim(1).claims().getFirst();
        Clock later=Clock.fixed(NOW.plusSeconds(11),ZoneOffset.UTC);var replacement=scans(later,1);
        assertEquals(1,replacement.claim(1).exhausted());
        assertTrue(recovery(later).runOne());assertFalse(recovery(later).runOne());
        assertEquals(1,scalar("SELECT count(*) FROM fs.upload_terminal_recovery WHERE reason='SCAN_DEAD' AND cleanup_disposition='HOLD'"));
        assertEquals(1,scalar("SELECT count(*) FROM fs.file_version WHERE state='FAILED'"));
        assertEquals(0,scalar("SELECT count(*) FROM fs.upload_scan_evidence"));
        assertEquals(0,scalar("SELECT count(*) FROM fs.storage_operation WHERE kind='CLEANUP_UPLOAD'"));
        assertEquals(1,fixture.opens.get());
    }

    private DurableDownloadAuthority downloads(Clock clock){
        return new DurableDownloadAuthority(secured(clock),new JdbcDownloadTransfers(source,clock,Duration.ofMinutes(1),5));
    }
    private static HttpRequest downloadRequest(String uri,String proof,String handle){
        return HttpRequest.newBuilder(URI.create(uri)).header("Authorization","Bearer "+proof)
            .header("X-Transfer-Handle",handle).GET().build();
    }
    private FileId fileId(){return new FileId(view.file().fileId());}
    private IntegrityFixture available() throws Exception {
        var fixture=matched();var scans=scans(CLOCK,3);
        new UploadCompletion(secured,scans).begin("user",TENANT,view.session(),null);
        assertEquals(UploadScanWorker.Outcome.COMPLETED,worker(scans,fixture,scanner(Scan.CLEAN,"text/plain"),CLOCK).runOne());
        return fixture;
    }

    @Test void downloadRequiresExplicitGrantAndExactAvailableVersion() throws Exception {
        grant("fixture-user","asset","DOWNLOAD_TRANSFER","OWNED_FILE",null);
        assertThrows(RuntimeException.class,()->downloads(CLOCK).issue("user",TENANT,fileId(),view.version(),null));
        var fixture=available();
        assertThrows(RuntimeException.class,()->downloads(CLOCK).issue("user",TENANT,fileId(),new VersionId(UUID.randomUUID()),null));
        assertThrows(RuntimeException.class,()->downloads(CLOCK).issue("other",TENANT,fileId(),view.version(),null));
        grant("fixture-other","asset","DOWNLOAD_TRANSFER","EXACT_FILE",fileId().value());
        var issued=downloads(CLOCK).issue("other",TENANT,fileId(),view.version(),null).value();
        assertThrows(TransferFailure.class,()->downloads(CLOCK).claim("user",issued.handle(),TransferInstruction.Action.DOWNLOAD));
        for(String proof:List.of("other-app","other-tenant","wrong"))
            assertThrows(TransferFailure.class,()->downloads(CLOCK).claim(proof,issued.handle(),TransferInstruction.Action.DOWNLOAD));
        assertThrows(TransferFailure.class,()->downloads(CLOCK).claim("other",issued.handle(),TransferInstruction.Action.UPLOAD));
        var pin=downloads(CLOCK).claim("other",issued.handle(),TransferInstruction.Action.DOWNLOAD);
        assertEquals(view.version(),pin.version());assertEquals(sha256(fixture.bytes),pin.sha256());
        assertThrows(TransferFailure.class,()->downloads(CLOCK).claim("other",issued.handle(),TransferInstruction.Action.DOWNLOAD));
        assertEquals(1,scalar("SELECT count(*) FROM fs.download_transfer_handle WHERE claimed_at IS NOT NULL"));
        assertEquals(0,scalar("SELECT count(*) FROM fs_kernel.platform_event_outbox WHERE payload_json::text LIKE ?","%"+issued.handle()+"%"));
    }

    @Test void downloadClaimFencesRevocationExpiryAndConcurrentReplay() throws Exception {
        available();grant("fixture-user","asset","DOWNLOAD_TRANSFER","OWNED_FILE",null);
        var d=downloads(CLOCK);var issued=d.issue("user",TENANT,fileId(),view.version(),null).value();
        sql("UPDATE fs.file_grant SET revoked=true WHERE action='DOWNLOAD_TRANSFER'");
        assertThrows(TransferFailure.class,()->d.claim("user",issued.handle(),TransferInstruction.Action.DOWNLOAD));
        sql("UPDATE fs.file_grant SET revoked=false WHERE action='DOWNLOAD_TRANSFER'");
        assertThrows(TransferFailure.class,()->downloads(Clock.fixed(NOW.plusSeconds(60),ZoneOffset.UTC)).claim("user",issued.handle(),TransferInstruction.Action.DOWNLOAD));
        var start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            Callable<Boolean> claim=()->{start.await();try{d.claim("user",issued.handle(),TransferInstruction.Action.DOWNLOAD);return true;}catch(TransferFailure ex){return false;}};
            var a=executor.submit(claim);var b=executor.submit(claim);start.countDown();
            assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,scalar("SELECT count(*) FROM fs.download_transfer_handle WHERE claimed_at IS NOT NULL"));
        assertEquals(1,scalar("SELECT count(*) FROM fs_kernel.platform_event_outbox WHERE event_type='file-storage.transfer.download.claimed'"));
    }

    @Test void downloadDeniesVerifyingAndQuarantine() throws Exception {
        var fixture=matched();var scans=scans(CLOCK,3);
        new UploadCompletion(secured,scans).begin("user",TENANT,view.session(),null);
        grant("fixture-user","asset","DOWNLOAD_TRANSFER","OWNED_FILE",null);
        assertThrows(RuntimeException.class,()->downloads(CLOCK).issue("user",TENANT,fileId(),view.version(),null));
        assertEquals(UploadScanWorker.Outcome.QUARANTINED,worker(scans,fixture,scanner(Scan.MALICIOUS,"text/plain"),CLOCK).runOne());
        assertThrows(RuntimeException.class,()->downloads(CLOCK).issue("user",TENANT,fileId(),view.version(),null));
        assertEquals(0,scalar("SELECT count(*) FROM fs.download_transfer_handle"));
    }

    @Test void downloadDriftAndIntentFailureNeverConsumeHandle() throws Exception {
        var fixture=available();grant("fixture-user","asset","DOWNLOAD_TRANSFER","OWNED_FILE",null);
        var d=downloads(CLOCK);var issued=d.issue("user",TENANT,fileId(),view.version(),null).value();
        String key=objectKey();
        sql("UPDATE fs.file_version SET object_key='private-prefix/drifted'");
        assertThrows(TransferFailure.class,()->d.claim("user",issued.handle(),TransferInstruction.Action.DOWNLOAD));
        sql("UPDATE fs.file_version SET object_key=?",key);
        sql("ALTER TABLE fs_kernel.platform_event_outbox ADD CONSTRAINT reject_download CHECK (event_type <> 'file-storage.transfer.download.claimed') NOT VALID");
        try{
            assertThrows(TransferFailure.class,()->d.claim("user",issued.handle(),TransferInstruction.Action.DOWNLOAD));
            assertEquals(0,scalar("SELECT count(*) FROM fs.download_transfer_handle WHERE claimed_at IS NOT NULL"));
        }finally{sql("ALTER TABLE fs_kernel.platform_event_outbox DROP CONSTRAINT reject_download");}
        var gateway=new PrivateTransferGateway(d,fixture,new TransferLimits(1024,1,1024,Duration.ofSeconds(5)),CLOCK);
        fixture.bytes="wrong".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var output=new java.io.ByteArrayOutputStream();
        assertThrows(TransferFailure.class,()->gateway.download("user",issued.handle(),output));
        assertEquals(0,output.size(),"final chunk withheld on checksum mismatch");
        assertEquals(1,scalar("SELECT count(*) FROM fs.download_transfer_handle WHERE claimed_at IS NOT NULL"));
    }

    @Test void catalogPagesOnlyAuthorizedFilesAndUsesSameScopeForUsage() throws Exception {
        assertThrows(RuntimeException.class,()->secured.catalog("user",TENANT,null,null,10));
        grant("fixture-user","asset","READ_METADATA","OWNED_FILE",null);
        assertThrows(RuntimeException.class,()->secured.catalog("user",TENANT,null,null,10));
        grant("fixture-user","asset","LIST_METADATA","OWNED_FILE",null);
        grant("fixture-user","asset","LIST_METADATA","EXACT_FILE",fileId().value());
        grant("fixture-other","asset","INITIATE","INITIATE_PURPOSE",null);
        var hidden=secured.initiate("other",TENANT,null,new Initiate("attachment","hidden.txt",77,"text/plain","hidden-key")).value();
        var second=secured.initiate("user",TENANT,null,new Initiate("attachment","second.txt",Long.MAX_VALUE,"text/plain","second-key")).value();
        var third=secured.initiate("user",TENANT,null,new Initiate("attachment","third.txt",Long.MAX_VALUE,"text/plain","third-key")).value();
        var first=secured.catalog("user",TENANT,null,null,1).value();
        assertEquals(1,first.items().size());assertNotNull(first.nextAfter());
        var middle=secured.catalog("user",TENANT,null,first.nextAfter(),1).value();
        assertEquals(1,middle.items().size());assertNotNull(middle.nextAfter());
        var last=secured.catalog("user",TENANT,null,middle.nextAfter(),1).value();
        assertEquals(1,last.items().size());assertNull(last.nextAfter());
        var actual=List.of(first.items().getFirst().file().fileId(),middle.items().getFirst().file().fileId(),last.items().getFirst().file().fileId());
        var expected=new ArrayList<>(List.of(view.file().fileId(),second.file().fileId(),third.file().fileId()));
        Collections.sort(expected);assertEquals(expected,actual);assertFalse(actual.contains(hidden.file().fileId()));
        var usage=secured.usage("user",TENANT,null).value();
        assertEquals(5,usage.states().size());
        var pending=usage.states().stream().filter(x->x.state()==VersionState.PENDING).findFirst().orElseThrow();
        assertEquals(3,pending.fileCount());assertEquals("18446744073709551619",pending.declaredBytes());assertEquals("0",pending.availableBytes());
        assertThrows(RuntimeException.class,()->secured.catalog("other-app",TENANT,null,null,10));
        assertThrows(RuntimeException.class,()->secured.catalog("other-tenant",TENANT,null,null,10));
        grant("fixture-other","asset","LIST_METADATA","EXACT_FILE",view.file().fileId());
        assertEquals(List.of(view.file().fileId()),secured.catalog("other",TENANT,null,null,100).value().items().stream().map(x->x.file().fileId()).toList());
        assertEquals(1,secured.usage("other",TENANT,null).value().states().stream().mapToLong(UsageBucket::fileCount).sum());
        sql("UPDATE fs.file_grant SET revoked=true WHERE action='LIST_METADATA' AND actor='fixture-user' AND scope='OWNED_FILE'");
        assertEquals(1,secured.catalog("user",TENANT,null,null,100).value().items().size());
        sql("UPDATE fs.file_grant SET revoked=true WHERE action='LIST_METADATA' AND actor='fixture-user'");
        assertThrows(RuntimeException.class,()->secured.catalog("user",TENANT,null,first.nextAfter(),1));
        assertThrows(RuntimeException.class,()->secured.usage("user",TENANT,null));
    }

    @Test void catalogRequiresExternalAndLocalScopeIntersection() throws Exception {
        grant("fixture-user","asset","LIST_METADATA","OWNED_FILE",null);
        var second=secured.initiate("user",TENANT,null,new Initiate("attachment","second.txt",7,"text/plain","second-key")).value();
        var external=RuntimeComposition.secured(source,new Trust(ISS,AUD),proof->new VerifiedAccess(ISS,"user",Set.of(AUD),TokenKind.ACCESS,NOW,NOW.plusSeconds(300),NOW),
            (issuer,subject,audience)->Optional.of(new Binding(USER,TENANT,"asset",Set.of(Action.LIST_METADATA))),
            (actor,tenant)->true,(tenant,module)->new ModuleEntitlement(TENANT.value(),"file-storage",true,NOW),
            caller->List.of(new Grant(TENANT,USER.actorId(),"asset",Action.LIST_METADATA,"attachment",GrantScope.EXACT_FILE,
                fileId(),NOW,NOW.plusSeconds(300),false)),CLOCK,Duration.ofMinutes(5),Duration.ofSeconds(30));
        assertEquals(List.of(view.file().fileId()),external.catalog("user",TENANT,null,null,100).value().items().stream().map(x->x.file().fileId()).toList());
        assertEquals(1,external.usage("user",TENANT,null).value().states().stream().mapToLong(UsageBucket::fileCount).sum());
        sql("UPDATE fs.file_grant SET revoked=true WHERE action='LIST_METADATA'");
        grant("fixture-user","asset","LIST_METADATA","EXACT_FILE",second.file().fileId());
        assertTrue(external.catalog("user",TENANT,null,null,100).value().items().isEmpty());
        assertEquals(0,external.usage("user",TENANT,null).value().states().stream().mapToLong(UsageBucket::fileCount).sum());
    }

    @Test void catalogHttpBoundsRedactionAndAvailableUsage() throws Exception {
        available();
        grant("fixture-user","asset","LIST_METADATA","OWNED_FILE",null);
        var pending=secured.initiate("user",TENANT,null,new Initiate("attachment","pending.txt",8,"text/plain","pending-key")).value();
        var app=RuntimeApplication.application(io.vyrnforge.filestorage.runtime.http.ApiHttpConfiguration.class);
        app.addInitializers(ctx->ctx.getBeanFactory().registerSingleton("securedFiles",secured));
        try(var ctx=app.run("--server.port=0","--fs.role=API");var http=HttpClient.newHttpClient()){
            String base="http://127.0.0.1:"+ctx.getEnvironment().getProperty("local.server.port");
            var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
            var first=catalogGet(http,base,"/v1/files?limit=1","user",TENANT);
            assertEquals(200,first.statusCode(),first.body());OpenApiContract.assertResponse(first);
            var json=mapper.readTree(first.body());assertEquals(1,json.get("items").size());
            String cursor=json.get("nextAfterFileId").asText();
            var last=catalogGet(http,base,"/v1/files?limit=1&afterFileId="+cursor,"user",TENANT);
            assertEquals(200,last.statusCode(),last.body());OpenApiContract.assertResponse(last);
            assertTrue(mapper.readTree(last.body()).get("nextAfterFileId").isNull());
            for(String path:List.of("/v1/files?limit=0","/v1/files?limit=101","/v1/files?limit=1&limit=2","/v1/files?afterFileId=","/v1/files?tenant=other","/v1/usage?limit=1")){
                var bad=catalogGet(http,base,path,"user",TENANT);assertEquals(400,bad.statusCode(),bad.body());OpenApiContract.assertResponse(bad);
            }
            var usage=catalogGet(http,base,"/v1/usage","user",TENANT);
            assertEquals(200,usage.statusCode(),usage.body());OpenApiContract.assertResponse(usage);
            var states=mapper.readTree(usage.body()).get("states");
            assertEquals(5,states.size());assertEquals("PENDING",states.get(0).get("state").asText());
            assertEquals("8",states.get(0).get("declaredBytes").asText());
            assertEquals("AVAILABLE",states.get(2).get("state").asText());assertEquals("5",states.get(2).get("availableBytes").asText());
            for(var response:List.of(first,last,usage)){
                assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
                for(String secret:List.of("fixture-bucket","private-prefix","ref:fixture","objectKey","sessionId","backendId","locationId"))
                    assertFalse(response.body().contains(secret));
            }
            assertEquals(403,catalogGet(http,base,"/v1/files","other",TENANT).statusCode());
            assertEquals(403,catalogGet(http,base,"/v1/usage","user",OTHER).statusCode());
            assertEquals(401,catalogGet(http,base,"/v1/files","wrong",TENANT).statusCode());
        }
    }
    @Test void logicalLifecycleFencesHandlesAndPreservesReference() throws Exception {
        available();
        for(String action:List.of("DELETE_FILE","RESTORE_FILE","READ_METADATA","LIST_METADATA","DOWNLOAD_TRANSFER"))
            grant("fixture-user","asset",action,"OWNED_FILE",null);
        var d=downloads(CLOCK);
        var stale=d.issue("user",TENANT,fileId(),view.version(),null).value();
        var deleted=secured.lifecycle("user",TENANT,null,fileId(),Action.DELETE_FILE,0).value();
        assertEquals(FileState.SOFT_DELETED,deleted.fileState());assertEquals(1,deleted.lifecycleRevision());
        assertEquals(view.file(),deleted.file());
        assertEquals(deleted,secured.lifecycle("user",TENANT,null,fileId(),Action.DELETE_FILE,0).value());
        assertEquals(Code.NOT_FOUND,assertThrows(Failure.class,()->secured.metadata("user",TENANT,null,fileId())).code());
        assertEquals(Code.NOT_FOUND,assertThrows(Failure.class,()->secured.session("user",TENANT,null,view.session())).code());
        assertThrows(RuntimeException.class,()->secured.initiate("user",TENANT,null,new Initiate("attachment","sample.txt",5,"text/plain","fixture-key")));
        assertEquals(1,scalar("SELECT count(*) FROM fs.file"));
        assertTrue(secured.catalog("user",TENANT,null,null,10).value().items().isEmpty());
        assertEquals(0,secured.usage("user",TENANT,null).value().states().stream().mapToLong(UsageBucket::fileCount).sum());
        assertThrows(RuntimeException.class,()->d.issue("user",TENANT,fileId(),view.version(),null));
        assertThrows(TransferFailure.class,()->d.claim("user",stale.handle(),TransferInstruction.Action.DOWNLOAD));
        var restored=secured.lifecycle("user",TENANT,null,fileId(),Action.RESTORE_FILE,1).value();
        assertEquals(FileState.ACTIVE,restored.fileState());assertEquals(2,restored.lifecycleRevision());
        assertEquals(view.file(),restored.file());
        assertEquals(view.version(),secured.metadata("user",TENANT,null,fileId()).value().version());
        assertEquals(2,secured.metadata("user",TENANT,null,fileId()).value().lifecycleRevision());
        assertEquals(1,secured.catalog("user",TENANT,null,null,10).value().items().size());
        assertEquals(Code.CONFLICT,assertThrows(Failure.class,()->secured.lifecycle("user",TENANT,null,fileId(),Action.DELETE_FILE,0)).code());
        assertThrows(TransferFailure.class,()->d.claim("user",stale.handle(),TransferInstruction.Action.DOWNLOAD));
        var fresh=d.issue("user",TENANT,fileId(),view.version(),null).value();
        assertEquals(fileId(),d.claim("user",fresh.handle(),TransferInstruction.Action.DOWNLOAD).file());
        assertEquals(1,scalar("SELECT count(*) FROM fs_kernel.platform_event_outbox WHERE event_type='file-storage.file.soft-deleted'"));
        assertEquals(1,scalar("SELECT count(*) FROM fs_kernel.platform_event_outbox WHERE event_type='file-storage.file.restored'"));
        assertEquals(1,scalar("SELECT count(*) FROM fs.file_version WHERE state='AVAILABLE'"));
        assertEquals(0,scalar("SELECT count(*) FROM fs.storage_operation WHERE kind='CLEANUP_UPLOAD'"));
    }

    @Test void logicalLifecycleRequiresBothAuthoritiesAndRollsBackIntent() throws Exception {
        grant("fixture-user","asset","DELETE_FILE","OWNED_FILE",null);
        assertEquals(Code.CONFLICT,assertThrows(Failure.class,()->secured.lifecycle("user",TENANT,null,fileId(),Action.DELETE_FILE,0)).code());
        available();
        assertThrows(RuntimeException.class,()->secured.lifecycle("other",TENANT,null,fileId(),Action.DELETE_FILE,0));
        assertThrows(RuntimeException.class,()->secured.lifecycle("other-app",TENANT,null,fileId(),Action.DELETE_FILE,0));
        assertThrows(RuntimeException.class,()->secured.lifecycle("other-tenant",TENANT,null,fileId(),Action.DELETE_FILE,0));
        var external=RuntimeComposition.secured(source,new Trust(ISS,AUD),proof->new VerifiedAccess(ISS,"user",Set.of(AUD),TokenKind.ACCESS,NOW,NOW.plusSeconds(300),NOW),
            (issuer,subject,audience)->Optional.of(new Binding(USER,TENANT,"asset",Set.of(Action.DELETE_FILE))),
            (actor,tenant)->true,(tenant,module)->new ModuleEntitlement(TENANT.value(),"file-storage",true,NOW),
            caller->List.of(new Grant(TENANT,USER.actorId(),"asset",Action.DELETE_FILE,"attachment",GrantScope.EXACT_FILE,
                fileId(),NOW,NOW.plusSeconds(300),false)),CLOCK,Duration.ofMinutes(5),Duration.ofSeconds(30));
        sql("UPDATE fs.file_grant SET revoked=true WHERE action='DELETE_FILE'");
        assertEquals(Code.NOT_FOUND,assertThrows(Failure.class,()->external.lifecycle("user",TENANT,null,fileId(),Action.DELETE_FILE,0)).code());
        sql("UPDATE fs.file_grant SET revoked=false WHERE action='DELETE_FILE'");
        sql("ALTER TABLE fs_kernel.platform_event_outbox ADD CONSTRAINT reject_lifecycle CHECK (event_type <> 'file-storage.file.soft-deleted') NOT VALID");
        try{
            assertThrows(RuntimeException.class,()->external.lifecycle("user",TENANT,null,fileId(),Action.DELETE_FILE,0));
            assertEquals(0,scalar("SELECT lifecycle_revision FROM fs.file"));
            assertEquals(0,scalar("SELECT count(*) FROM fs_kernel.platform_audit_event WHERE action='file-storage.file.soft-deleted'"));
        }finally{sql("ALTER TABLE fs_kernel.platform_event_outbox DROP CONSTRAINT reject_lifecycle");}
        assertEquals(1,external.lifecycle("user",TENANT,null,fileId(),Action.DELETE_FILE,0).value().lifecycleRevision());
        assertThrows(RuntimeException.class,()->secured.lifecycle("user",TENANT,null,fileId(),Action.RESTORE_FILE,1));
        grant("fixture-other","asset","RESTORE_FILE","EXACT_FILE",fileId().value());
        assertEquals(2,secured.lifecycle("other",TENANT,null,fileId(),Action.RESTORE_FILE,1).value().lifecycleRevision());
    }

    @Test void logicalLifecycleHttpContractAndConcurrentRetries() throws Exception {
        available();
        for(String action:List.of("DELETE_FILE","RESTORE_FILE","READ_METADATA","LIST_METADATA"))
            grant("fixture-user","asset",action,"OWNED_FILE",null);
        var app=RuntimeApplication.application(io.vyrnforge.filestorage.runtime.http.ApiHttpConfiguration.class);
        app.addInitializers(ctx->ctx.getBeanFactory().registerSingleton("securedFiles",secured));
        try(var ctx=app.run("--server.port=0","--fs.role=API");var http=HttpClient.newHttpClient()){
            String base="http://127.0.0.1:"+ctx.getEnvironment().getProperty("local.server.port");
            String path="/v1/files/"+fileId().value();
            var request=HttpRequest.newBuilder(URI.create(base+path+"/delete?expectedRevision=0"))
                .header("Authorization","Bearer user").header("X-Tenant-Id",TENANT.value().toString())
                .POST(HttpRequest.BodyPublishers.noBody()).build();
            var one=http.sendAsync(request,HttpResponse.BodyHandlers.ofString());
            var two=http.sendAsync(request,HttpResponse.BodyHandlers.ofString());
            for(var response:List.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS))){
                assertEquals(200,response.statusCode(),response.body());OpenApiContract.assertResponse(response);
                assertTrue(response.body().contains("SOFT_DELETED"));
            }
            assertEquals(1,scalar("SELECT count(*) FROM fs_kernel.platform_event_outbox WHERE event_type='file-storage.file.soft-deleted'"));
            var hidden=catalogGet(http,base,path,"user",TENANT);assertEquals(404,hidden.statusCode());OpenApiContract.assertResponse(hidden);
            var list=catalogGet(http,base,"/v1/files","user",TENANT);assertEquals(200,list.statusCode());OpenApiContract.assertResponse(list);
            assertTrue(tools.jackson.databind.json.JsonMapper.builder().build().readTree(list.body()).get("items").isEmpty());
            var restore=HttpRequest.newBuilder(URI.create(base+path+"/restore?expectedRevision=1"))
                .header("Authorization","Bearer user").header("X-Tenant-Id",TENANT.value().toString())
                .POST(HttpRequest.BodyPublishers.noBody()).build();
            var restored=http.send(restore,HttpResponse.BodyHandlers.ofString());
            assertEquals(200,restored.statusCode(),restored.body());OpenApiContract.assertResponse(restored);
            var metadata=catalogGet(http,base,path,"user",TENANT);assertEquals(200,metadata.statusCode());OpenApiContract.assertResponse(metadata);
            assertEquals(2,tools.jackson.databind.json.JsonMapper.builder().build().readTree(metadata.body()).get("lifecycleRevision").asLong());
        }
    }

    @Test void deletedCatalogScopesPaginationAndRecoveryAcrossRequests() throws Exception {
        available();
        for(String action:List.of("DELETE_FILE","RESTORE_FILE","LIST_METADATA"))
            grant("fixture-user","asset",action,"OWNED_FILE",null);
        var first=fileId();
        secured.lifecycle("user",TENANT,null,first,Action.DELETE_FILE,0);
        assertThrows(RuntimeException.class,()->secured.deleted("user",TENANT,null,null,1));
        grant("fixture-user","asset","LIST_DELETED_METADATA","OWNED_FILE",null);
        grant("fixture-user","asset","LIST_DELETED_METADATA","EXACT_FILE",first.value());
        view=secured.initiate("user",TENANT,null,new Initiate("attachment","second.txt",5,"text/plain","second-deleted")).value();
        available();
        var second=fileId();
        secured.lifecycle("user",TENANT,null,second,Action.DELETE_FILE,0);
        var page=secured.deleted("user",TENANT,null,null,1).value();
        assertEquals(1,page.items().size());assertNotNull(page.nextAfter());
        assertEquals(NOW,page.items().getFirst().deletedAt());
        assertEquals(1,page.items().getFirst().metadata().lifecycleRevision());
        var last=secured.deleted("user",TENANT,null,page.nextAfter(),1).value();
        assertEquals(1,last.items().size());assertNull(last.nextAfter());
        assertEquals(Set.of(first.value(),second.value()),Set.of(page.items().getFirst().metadata().file().fileId(),last.items().getFirst().metadata().file().fileId()));
        assertTrue(secured.catalog("user",TENANT,null,null,100).value().items().isEmpty());
        assertEquals(0,secured.usage("user",TENANT,null).value().states().stream().mapToLong(UsageBucket::fileCount).sum());
        assertThrows(RuntimeException.class,()->secured.deleted("other-app",TENANT,null,null,100));
        assertThrows(RuntimeException.class,()->secured.deleted("other-tenant",TENANT,null,null,100));
        grant("fixture-other","asset","LIST_DELETED_METADATA","EXACT_FILE",first.value());
        assertEquals(first.value(),secured.deleted("other",TENANT,null,null,100).value().items().getFirst().metadata().file().fileId());
        assertThrows(RuntimeException.class,()->secured.lifecycle("other",TENANT,null,first,Action.RESTORE_FILE,1));
        var external=RuntimeComposition.secured(source,new Trust(ISS,AUD),proof->new VerifiedAccess(ISS,"user",Set.of(AUD),TokenKind.ACCESS,NOW,NOW.plusSeconds(300),NOW),
            (issuer,subject,audience)->Optional.of(new Binding(USER,TENANT,"asset",Set.of(Action.LIST_DELETED_METADATA))),
            (actor,tenant)->true,(tenant,module)->new ModuleEntitlement(TENANT.value(),"file-storage",true,NOW),
            caller->List.of(new Grant(TENANT,USER.actorId(),"asset",Action.LIST_DELETED_METADATA,"attachment",GrantScope.EXACT_FILE,
                first,NOW,NOW.plusSeconds(300),false)),CLOCK,Duration.ofMinutes(5),Duration.ofSeconds(30));
        assertEquals(1,external.deleted("user",TENANT,null,null,100).value().items().size());
        sql("UPDATE fs.file_grant SET revoked=true WHERE actor='fixture-user' AND action='LIST_DELETED_METADATA'");
        grant("fixture-user","asset","LIST_DELETED_METADATA","EXACT_FILE",second.value());
        assertTrue(external.deleted("user",TENANT,null,null,100).value().items().isEmpty());
        var app=RuntimeApplication.application(io.vyrnforge.filestorage.runtime.http.ApiHttpConfiguration.class);
        app.addInitializers(ctx->ctx.getBeanFactory().registerSingleton("securedFiles",secured));
        try(var ctx=app.run("--server.port=0","--fs.role=API");var http=HttpClient.newHttpClient()){
            String base="http://127.0.0.1:"+ctx.getEnvironment().getProperty("local.server.port");
            var result=catalogGet(http,base,"/v1/deleted-files?limit=1","user",TENANT);
            assertEquals(200,result.statusCode(),result.body());OpenApiContract.assertResponse(result);
            assertTrue(result.body().contains(second.value()));assertFalse(result.body().contains(first.value()));
            for(String secret:List.of("fixture-bucket","private-prefix","ref:fixture","objectKey","sessionId","backendId","locationId","holdState","retainUntil"))
                assertFalse(result.body().contains(secret));
            for(String path:List.of("/v1/deleted-files?limit=0","/v1/deleted-files?limit=101","/v1/deleted-files?limit=1&limit=2","/v1/deleted-files?afterFileId=","/v1/deleted-files?state=ACTIVE")){
                var bad=catalogGet(http,base,path,"user",TENANT);assertEquals(400,bad.statusCode(),bad.body());OpenApiContract.assertResponse(bad);
            }
        }
        secured.lifecycle("user",TENANT,null,second,Action.RESTORE_FILE,1);
        assertTrue(secured.deleted("user",TENANT,null,null,100).value().items().isEmpty());
        assertEquals(1,secured.catalog("user",TENANT,null,null,100).value().items().size());
    }

    @Test void retentionEvidenceFailsClosedAndNeverAuthorizesPurge() throws Exception {
        available();
        grant("fixture-user","asset","DELETE_FILE","OWNED_FILE",null);
        grant("fixture-user","asset","RESTORE_FILE","OWNED_FILE",null);
        var safety=new JdbcRetentionSafety(source,CLOCK);
        assertEquals(io.vyrnforge.filestorage.domain.RetentionSafety.Assessment.NOT_DELETED,safety.assess(TENANT,fileId()));
        secured.lifecycle("user",TENANT,null,fileId(),Action.DELETE_FILE,0);
        assertEquals(io.vyrnforge.filestorage.domain.RetentionSafety.Assessment.EVIDENCE_MISSING,safety.assess(TENANT,fileId()));
        assertEquals(io.vyrnforge.filestorage.domain.RetentionSafety.Assessment.EVIDENCE_MISSING,safety.assess(OTHER,fileId()));
        sql("""
            INSERT INTO fs.file_retention_evidence(tenant_id,file_id,lifecycle_revision,policy_revision,hold_state,retain_until,observed_at,valid_until)
            VALUES (?,?,1,1,'UNKNOWN',NULL,?,?)
            """,TENANT.value(),fileId().value(),NOW.minusSeconds(1).atOffset(ZoneOffset.UTC),NOW.plusSeconds(60).atOffset(ZoneOffset.UTC));
        assertEquals(io.vyrnforge.filestorage.domain.RetentionSafety.Assessment.HOLD_UNKNOWN,safety.assess(TENANT,fileId()));
        sql("UPDATE fs.file_retention_evidence SET hold_state='HELD'");
        assertEquals(io.vyrnforge.filestorage.domain.RetentionSafety.Assessment.HELD,safety.assess(TENANT,fileId()));
        sql("UPDATE fs.file_retention_evidence SET hold_state='CLEAR'");
        assertEquals(io.vyrnforge.filestorage.domain.RetentionSafety.Assessment.RETENTION_UNRESOLVED,safety.assess(TENANT,fileId()));
        sql("UPDATE fs.file_retention_evidence SET retain_until=?",NOW.plusSeconds(30).atOffset(ZoneOffset.UTC));
        assertEquals(io.vyrnforge.filestorage.domain.RetentionSafety.Assessment.RETENTION_ACTIVE,safety.assess(TENANT,fileId()));
        sql("UPDATE fs.file_retention_evidence SET retain_until=?",NOW.atOffset(ZoneOffset.UTC));
        assertEquals(io.vyrnforge.filestorage.domain.RetentionSafety.Assessment.REVIEW_REQUIRED,safety.assess(TENANT,fileId()));
        var expired=new JdbcRetentionSafety(source,Clock.fixed(NOW.plusSeconds(60),ZoneOffset.UTC));
        assertEquals(io.vyrnforge.filestorage.domain.RetentionSafety.Assessment.EVIDENCE_STALE,expired.assess(TENANT,fileId()));
        assertThrows(SQLException.class,()->sql("UPDATE fs.file SET state='PURGE_PENDING'"));
        assertThrows(SQLException.class,()->sql("UPDATE fs.file SET state='PURGED'"));
        secured.lifecycle("user",TENANT,null,fileId(),Action.RESTORE_FILE,1);
        secured.lifecycle("user",TENANT,null,fileId(),Action.DELETE_FILE,2);
        assertEquals(io.vyrnforge.filestorage.domain.RetentionSafety.Assessment.EVIDENCE_MISSING,safety.assess(TENANT,fileId()));
        assertEquals(1,scalar("SELECT count(*) FROM fs.file_version WHERE state='AVAILABLE'"));
        assertEquals(0,scalar("SELECT count(*) FROM fs.storage_operation WHERE kind='CLEANUP_UPLOAD'"));
    }

    private static HttpRequest lifecycleRequest(String uri){
        return HttpRequest.newBuilder(URI.create(uri)).header("Authorization","Bearer user")
            .header("X-Tenant-Id",TENANT.value().toString()).POST(HttpRequest.BodyPublishers.noBody()).build();
    }

    private static HttpResponse<String> catalogGet(HttpClient http,String base,String path,String proof,TenantId tenant)throws Exception{
        return http.send(HttpRequest.newBuilder(URI.create(base+path)).header("Authorization","Bearer "+proof)
            .header("X-Tenant-Id",tenant.value().toString()).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
}
