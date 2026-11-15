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
}
