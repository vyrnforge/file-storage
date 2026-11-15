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
                var gateway = new PrivateTransferGateway(authority(),resolver,
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
                    assertEquals(1,scalar("SELECT count(*) FROM fs.upload_transfer_handle WHERE claimed_at IS NOT NULL"));
                    assertEquals(1,scalar("SELECT count(*) FROM fs.file_version WHERE state='PENDING'"));
                    assertEquals(1,scalar("SELECT count(*) FROM fs.upload_session WHERE state='INITIATED'"));
                    try(var files=Files.list(spool)){assertEquals(0,files.count());}
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
}
