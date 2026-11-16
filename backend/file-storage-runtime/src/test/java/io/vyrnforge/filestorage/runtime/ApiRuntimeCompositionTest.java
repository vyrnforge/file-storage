package io.vyrnforge.filestorage.runtime;

import io.vyrnforge.filestorage.application.BackendValidator;
import io.vyrnforge.filestorage.application.FileApplication.Action;
import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.filestorage.persistence.JdbcRegistry;
import io.vyrnforge.filestorage.persistence.JdbcConfiguration;
import io.vyrnforge.filestorage.security.SecuredFiles;
import io.vyrnforge.filestorage.security.SecuredFiles.*;
import io.vyrnforge.platform.kernel.context.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** All three API groups share the actual composition and one disposable database. */
class ApiRuntimeCompositionTest {
    private static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");
    private static PGSimpleDataSource source;
    private static RuntimeDatabaseRoles separated;
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String ISS = "https://iam.example.test", AUD = "file-storage-test";
    private static final TenantId TENANT = new TenantId(UUID.fromString("01900000-0000-7000-8000-000000000001"));
    private static final ActorReference USER = new ActorReference(ActorType.USER, "fixture-user", null, null);
    private static final ActorReference ADMIN = new ActorReference(ActorType.USER, "fixture-admin", null, null);
    private static final Scope SCOPE = new Scope(TENANT, "asset", "attachment");
    private static final Target TARGET = new Target(Provider.S3, URI.create("https://s3.example.test"), "test", Addressing.PATH);
    private static final String POLICY = "/v1/admin/policies/active?application=asset&purpose=attachment";
    private static final String BODY = """
            {"purpose":"attachment","name":"sample.txt","expectedSize":5,"mimeType":"text/plain"}
            """;

    @BeforeAll static void start() {
        DB.start();
        source = new PGSimpleDataSource();
        source.setURL(DB.getJdbcUrl());
        source.setUser(DB.getUsername());
        source.setPassword(DB.getPassword());
        source.setConnectTimeout(3);
        source.setSocketTimeout(5);
    }

    @AfterAll static void stop() { DB.stop(); }

    @BeforeEach void reset() throws Exception {
        sql("DROP SCHEMA IF EXISTS fs CASCADE");
        sql("DROP SCHEMA IF EXISTS fs_kernel CASCADE");
    }

    private static void sql(String statement) throws Exception {
        try (var connection = source.getConnection(); var st = connection.createStatement()) {
            st.execute(statement);
        }
    }

    private static long scalar(String query) throws Exception {
        try (var connection = source.getConnection(); var st = connection.createStatement(); var rs = st.executeQuery(query)) {
            rs.next(); return rs.getLong(1);
        }
    }

    private static BackendValidator.Report report(BackendValidator.Candidate candidate) {
        Map<LocationId, Set<BackendValidator.Check>> checks = new HashMap<>();
        for (var location : candidate.locations()) checks.put(location.id(), EnumSet.allOf(BackendValidator.Check.class));
        return new BackendValidator.Report(candidate.backend(), candidate.revision(), checks);
    }

    private static void seed() {
        var registry = new JdbcRegistry(source, CLOCK);
        var config = new JdbcConfiguration(source, CLOCK);
        var context = CorrelationContext.create();
        var backend = registry.registerBackend(ADMIN, context, TARGET, new CredentialRef("ref:fixture/v1"));
        var location = registry.registerLocation(ADMIN, context, backend, "fixture-bucket", "private-prefix");
        registry.validateDraft(ADMIN, context, backend, 1, Set.of(TARGET), ApiRuntimeCompositionTest::report);
        config.activateBackend(ADMIN, context, backend, 0, 1);
        config.publishPolicy(ADMIN, context, SCOPE, 0, List.of(new JdbcConfiguration.PolicyRule(location, 0, true)));
    }

    static final class Authority {
        volatile boolean available = true;
        boolean ready() { return available; }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(ApiRuntimeConfiguration.class)
    static class TestConfiguration {
        @Bean Authority authority() { return new Authority(); }

        @Bean ApiRuntimeDependencies dependencies(Authority authority) {
            return new ApiRuntimeDependencies(source, CLOCK, Duration.ofMinutes(5), Duration.ofSeconds(30),
                new Trust(ISS, AUD), bearer -> {
                    if (!authority.available || !Set.of("user", "admin").contains(bearer))
                        throw new IllegalArgumentException("private fixture detail");
                    return new VerifiedAccess(ISS, bearer, Set.of(AUD), TokenKind.ACCESS, NOW, NOW.plusSeconds(300), NOW);
                }, (actor, tenant) -> authority.available && tenant.equals(TENANT.value()),
                (tenant, module) -> new ModuleEntitlement(TENANT.value(), "file-storage", authority.available, NOW),
                new ApiRuntimeDependencies.ConsumerAuthority(
                    (issuer, subject, audience) -> "user".equals(subject)
                        ? Optional.of(new Binding(USER, TENANT, "asset", Set.of(Action.values()))) : Optional.empty(),
                    caller -> List.of(grant(Action.INITIATE, GrantScope.INITIATE_PURPOSE),
                            grant(Action.READ_SESSION, GrantScope.OWNED_FILE), grant(Action.READ_METADATA, GrantScope.OWNED_FILE))),
                new ApiRuntimeDependencies.PolicyAdministration(
                    (issuer, subject, audience, tenant) -> "admin".equals(subject) && TENANT.equals(tenant)
                        ? Optional.of(ADMIN) : Optional.empty(),
                    (actor, scope, action) -> authority.available && ADMIN.equals(actor) && SCOPE.equals(scope),
                    (actor, scope, locations) -> authority.available && ADMIN.equals(actor) && SCOPE.equals(scope)),
                new ApiRuntimeDependencies.BackendAuthority(
                    (issuer, subject, audience) -> "admin".equals(subject) ? Optional.of(ADMIN) : Optional.empty(),
                    (actor, action, backend) -> authority.available && ADMIN.equals(actor),
                    Set.of(TARGET), ApiRuntimeCompositionTest::report),
                authority::ready,separated);
        }

        private static Grant grant(Action action, GrantScope scope) {
            return new Grant(TENANT, USER.actorId(), "asset", action, "attachment", scope,
                    null, NOW, NOW.plusSeconds(300), false);
        }
    }

    private static ConfigurableApplicationContext application(String role) {
        return RuntimeApplication.application(TestConfiguration.class).run("--server.port=0", "--fs.role=" + role);
    }

    private static HttpResponse<String> call(ConfigurableApplicationContext ctx, String method, String path,
            String bearer, boolean tenant, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" +
                ctx.getEnvironment().getProperty("local.server.port") + path)).timeout(Duration.ofSeconds(10));
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (tenant) request.header("X-Tenant-Id", TENANT.value().toString());
        if (body != null) request.header("Content-Type", "application/json").header("Idempotency-Key", "composition-key");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (body != null && response.statusCode() == 200) OpenApiContract.assertRequest(response, body);
            return response;
        }
    }

    private static void status(int expected, HttpResponse<String> response) throws Exception {
        assertEquals(expected, response.statusCode(), response.body());
        if (response.request().uri().getPath().startsWith("/v1/") && expected != 404)
            OpenApiContract.assertResponse(response);
    }

    private static String field(String json, String name) {
        var match = Pattern.compile("\""+name+"\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        assertTrue(match.find(), json); return match.group(1);
    }

    @Test void explicitCompositionUsesRealDatabaseAndSeparateAuthorities() throws Exception {
        RuntimeComposition.migrate(source);
        seed();
        try (var ctx = application("API")) {
            status(200, call(ctx, "GET", "/health/ready", null, false, null));
            var mapping = ctx.getBean(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
            Set<String> actual = new HashSet<>();
            mapping.getHandlerMethods().keySet().forEach(info -> info.getPatternValues().stream()
                    .filter(path -> path.startsWith("/v1/")).forEach(path ->
                    info.getMethodsCondition().getMethods().forEach(method -> actual.add(method.name().toLowerCase(Locale.ROOT)+" "+path))));
            assertEquals(OpenApiContract.operations(), actual);
            status(200, call(ctx, "GET", "/v1/admin/backends", "admin", false, null));
            status(200, call(ctx, "GET", POLICY, "admin", true, null));
            status(403, call(ctx, "GET", "/v1/admin/backends", "user", false, null));
            status(403, call(ctx, "GET", POLICY, "user", true, null));
            var first = call(ctx, "POST", "/v1/uploads", "user", true, BODY);
            status(200, first);
            var file = field(first.body(), "fileId");
            var session = field(first.body(), "sessionId");
            var replay = call(ctx, "POST", "/v1/uploads", "user", true, BODY);
            status(200, replay); assertEquals(session, field(replay.body(), "sessionId"));
            status(200, call(ctx, "GET", "/v1/files/"+file, "user", true, null));
            status(200, call(ctx, "GET", "/v1/uploads/"+session, "user", true, null));
            assertEquals(1, scalar("SELECT count(*) FROM fs.file"));
            ctx.getBean(Authority.class).available = false;
            status(503, call(ctx, "GET", "/health/ready", null, false, null));
            status(200, call(ctx, "GET", "/health/live", null, false, null));
            status(401, call(ctx, "GET", "/v1/files/"+file, "user", true, null));
            ctx.getBean(Authority.class).available = true;
            status(200, call(ctx, "GET", "/health/ready", null, false, null));
        }
    }

    @Test void readinessDoesNotMigrateAndDetectsMissingOrInvalidHistory() throws Exception {
        try (var ctx = application("API")) {
            status(503, call(ctx, "GET", "/health/ready", null, false, null));
            assertEquals(0, scalar("SELECT count(*) FROM information_schema.schemata WHERE schema_name IN ('fs','fs_kernel')"));
            RuntimeComposition.migrate(source);
            status(200, call(ctx, "GET", "/health/ready", null, false, null));
            int[] ports = source.getPortNumbers();
            try {
                source.setPortNumbers(new int[]{1});
                status(503, call(ctx, "GET", "/health/ready", null, false, null));
                status(200, call(ctx, "GET", "/health/live", null, false, null));
            } finally { source.setPortNumbers(ports); }
            status(200, call(ctx, "GET", "/health/ready", null, false, null));
            sql("UPDATE fs.flyway_schema_history SET checksum=checksum+1 WHERE version='1'");
            status(503, call(ctx, "GET", "/health/ready", null, false, null));
            status(200, call(ctx, "GET", "/health/live", null, false, null));
        }
    }

    @Test void compositionRequiresExplicitDependenciesAndRoleReadinessRemainsHonest() throws Exception {
        RuntimeComposition.migrate(source);
        seed();
        try (var ctx = application("COMBINED")) {
            status(200, call(ctx, "GET", "/v1/admin/backends", "admin", false, null));
            status(503, call(ctx, "GET", "/health/ready", null, false, null));
        }
        assertThrows(RuntimeException.class, () -> {
            try (var ignored = application("TRANSFER")) { fail("metadata must not start"); }
        });
        assertThrows(RuntimeException.class, () -> {
            try (var ignored = RuntimeApplication.application(ApiRuntimeConfiguration.class).run("--server.port=0")) {
                fail("trusted dependencies must be supplied");
            }
        });
        try (var ctx = RuntimeApplication.start("--server.port=0")) {
            status(503, call(ctx, "GET", "/health/ready", null, false, null));
            status(404, call(ctx, "GET", "/v1/admin/backends", "admin", false, null));
        }
    }
    private static PGSimpleDataSource login(String name){
        var ds=new PGSimpleDataSource();ds.setURL(DB.getJdbcUrl());ds.setUser(name);ds.setPassword("fixture-only-a14");
        ds.setConnectTimeout(3);ds.setSocketTimeout(5);return ds;
    }
    private static void fixtureGrant(String action,String scope)throws Exception{
        try(var c=source.getConnection();var ps=c.prepareStatement("""
            INSERT INTO fs.file_grant(grant_id,tenant_id,actor,consumer,purpose,action,scope,not_before,expires_at)
            VALUES (?,?,?,?,?,?,?,?,?)
            """)){
            ps.setObject(1,UUID.randomUUID());ps.setObject(2,TENANT.value());ps.setString(3,USER.actorId());ps.setString(4,"asset");
            ps.setString(5,"attachment");ps.setString(6,action);ps.setString(7,scope);
            ps.setObject(8,NOW.minusSeconds(1).atOffset(ZoneOffset.UTC));ps.setObject(9,NOW.plusSeconds(300).atOffset(ZoneOffset.UTC));ps.executeUpdate();
        }
    }
    @Test void separatedCompositionRunsHttpLifecycleAndInternalCommandsWithRestrictedLogins() throws Exception {
        RuntimeComposition.migrate(source);seed();
        var roles=RuntimeDatabaseRoles.Role.values();
        var kinds=List.of(IsolatedRoleProfiles.Kind.METADATA_API,IsolatedRoleProfiles.Kind.LOGICAL,
            IsolatedRoleProfiles.Kind.PURGE_PREPARATION,IsolatedRoleProfiles.Kind.PLACEMENT,IsolatedRoleProfiles.Kind.BACKEND_ONLY,
            IsolatedRoleProfiles.Kind.GRANT_READ,IsolatedRoleProfiles.Kind.READINESS,IsolatedRoleProfiles.Kind.TRANSFER,
            IsolatedRoleProfiles.Kind.COMPLETION_WORKER,IsolatedRoleProfiles.Kind.GOVERNANCE);
        var bindings=new EnumMap<RuntimeDatabaseRoles.Role,RuntimeDatabaseRoles.Binding>(RuntimeDatabaseRoles.Role.class);
        try{
            for(int i=0;i<roles.length;i++){
                String group="a14_group_"+i,name="a14_login_"+i;
                var profile=new IsolatedRoleProfiles(kinds.get(i));profile.provision(source,group);
                sql("CREATE ROLE "+name+" LOGIN PASSWORD 'fixture-only-a14' NOSUPERUSER NOCREATEDB NOCREATEROLE");
                sql("GRANT "+group+" TO "+name);profile.verify(source,name,group);
                bindings.put(roles[i],new RuntimeDatabaseRoles.Binding(name,login(name)));
            }
            separated=new RuntimeDatabaseRoles(bindings);
            assertTrue(separated.ready());
            fixtureGrant("INITIATE","INITIATE_PURPOSE");
            for(String action:List.of("READ_SESSION","READ_METADATA","LIST_METADATA","UPLOAD_TRANSFER","DELETE_FILE","RESTORE_FILE","PREPARE_PURGE"))
                fixtureGrant(action,"OWNED_FILE");
            try(var ctx=RuntimeApplication.application(TestConfiguration.class).run("--server.port=0","--fs.role=API","--fs.require-separated-roles=true")){
                status(200,call(ctx,"GET","/health/ready",null,false,null));
                status(200,call(ctx,"GET","/v1/admin/backends","admin",false,null));
                status(200,call(ctx,"GET",POLICY,"admin",true,null));
                status(403,call(ctx,"GET",POLICY,"user",true,null));
                var created=call(ctx,"POST","/v1/uploads","user",true,BODY);status(200,created);
                var file=new FileId(field(created.body(),"fileId"));
                var session=new SessionId(UUID.fromString(field(created.body(),"sessionId")));
                var version=new VersionId(UUID.fromString(field(created.body(),"versionId")));
                var secured=ctx.getBean(SecuredFiles.class);
                var transfer=RuntimeComposition.uploadTransfers(separated,CLOCK,Duration.ofMinutes(1),5);
                var authority=new io.vyrnforge.filestorage.runtime.transfer.DurableUploadAuthority(secured,transfer);
                var handle=authority.issue("user",TENANT,session,null);
                authority.claim("user",handle.handle(),io.vyrnforge.filestorage.runtime.transfer.TransferInstruction.Action.UPLOAD);
                var work=RuntimeComposition.completionWork(separated,CLOCK,Duration.ofMinutes(1),5,Duration.ofSeconds(30),Duration.ZERO,3,ADMIN);
                var completion=new io.vyrnforge.filestorage.runtime.transfer.CompletionCommands(secured,transfer,work);
                String digest="a".repeat(64);
                completion.complete("user",TENANT,session,digest,null);
                var verify=work.load(work.claim().claims().getFirst());work.finish(verify,digest);
                var worker=separated.source(RuntimeDatabaseRoles.Role.WORKER);
                var scans=new io.vyrnforge.filestorage.persistence.JdbcUploadScan(
                    new io.vyrnforge.filestorage.persistence.JdbcUploadTransfers(worker,CLOCK,Duration.ofMinutes(1),5),
                    new io.vyrnforge.filestorage.persistence.JdbcOperations(worker,CLOCK,Duration.ofSeconds(30),Duration.ZERO,3),ADMIN,3);
                var scan=scans.load(scans.claim(1).claims().getFirst());
                scans.finish(scan,new Content(5,digest,"text/plain"),Scan.CLEAN);
                status(200,call(ctx,"GET","/v1/files/"+file.value(),"user",true,null));
                status(200,call(ctx,"POST","/v1/files/"+file.value()+"/delete?expectedRevision=0","user",true,null));
                status(404,call(ctx,"GET","/v1/files/"+file.value(),"user",true,null));
                // Fixture-only legacy retention evidence; no policy activation or actual deletion.
                sql("INSERT INTO fs.file_retention_evidence(tenant_id,file_id,lifecycle_revision,policy_revision,hold_state,retain_until,observed_at,valid_until) VALUES ('"+TENANT.value()+"','"+file.value()+"',1,1,'CLEAR','2026-01-01T00:00:00Z','2025-12-31T23:59:59Z','2026-01-01T00:01:00Z')");
                var prepared=secured.preparePurge("user",TENANT,null,new io.vyrnforge.filestorage.application.FileApplication.PurgeRequest(file,version,1,1)).value();
                assertNotNull(prepared.operationId());assertEquals(0,scalar("SELECT count(*) FROM fs.storage_operation WHERE kind='PURGE_VERSION'"));
                status(200,call(ctx,"POST","/v1/files/"+file.value()+"/restore?expectedRevision=1","user",true,null));
                var governance=RuntimeComposition.governance(separated,CLOCK,(actor,tenant,f,action)->TENANT.equals(tenant)
                    &&Set.of(ADMIN,USER).contains(actor),(claim,evidence,actor)->false);
                long revision=governance.draft(ADMIN,TENANT,new io.vyrnforge.filestorage.application.LifecycleGovernance.PolicyDraft(0,10,
                    io.vyrnforge.filestorage.application.LifecycleGovernance.QuotaMode.CHARGE_UNTIL_VERIFIED_PURGE),CorrelationContext.create());
                governance.review(USER,TENANT,revision,CorrelationContext.create());
                governance.assertHold(ADMIN,TENANT,file,"b".repeat(64),CorrelationContext.create());
                assertEquals(0,scalar("SELECT count(*) FROM fs.isolated_policy_activation"));
                assertEquals(1,scalar("SELECT count(*) FROM fs.governed_legal_hold"));
                try(var c=separated.source(RuntimeDatabaseRoles.Role.GRANT_READ).getConnection();var st=c.createStatement()){
                    assertEquals("42501",assertThrows(java.sql.SQLException.class,()->st.execute("UPDATE fs.file_grant SET revoked=true")).getSQLState());
                }
                try(var c=separated.source(RuntimeDatabaseRoles.Role.PURGE_PREPARATION).getConnection();var st=c.createStatement()){
                    assertEquals("42501",assertThrows(java.sql.SQLException.class,()->st.execute("UPDATE fs.file_retention_evidence SET hold_state='CLEAR'")).getSQLState());
                    assertEquals("23514",assertThrows(java.sql.SQLException.class,()->st.execute("UPDATE fs.file_retention_evidence SET file_id='changed'")).getSQLState());
                }
                sql("REVOKE a14_group_5 FROM a14_login_5");
                // Identity admission alone is not a privilege matrix: readiness checks required grants below.
                assertFalse(separated.ready());
                status(503,call(ctx,"GET","/health/ready",null,false,null));
                assertNotEquals(200,call(ctx,"GET","/v1/files/"+file.value(),"user",true,null).statusCode());
                sql("GRANT a14_group_5 TO a14_login_5");
                status(200,call(ctx,"GET","/health/ready",null,false,null));
            }
            var invalid=new EnumMap<RuntimeDatabaseRoles.Role,RuntimeDatabaseRoles.Binding>(bindings);
            invalid.put(RuntimeDatabaseRoles.Role.METADATA,new RuntimeDatabaseRoles.Binding("wrong_login",bindings.get(RuntimeDatabaseRoles.Role.METADATA).source()));
            separated=new RuntimeDatabaseRoles(invalid);
            assertFalse(separated.ready());
            assertThrows(RuntimeException.class,()->{try(var ignored=RuntimeApplication.application(TestConfiguration.class).run("--server.port=0","--fs.require-separated-roles=true")){fail("invalid identity started");}});
            invalid.put(RuntimeDatabaseRoles.Role.METADATA,new RuntimeDatabaseRoles.Binding(DB.getUsername(),source));
            separated=new RuntimeDatabaseRoles(invalid);assertFalse(separated.ready());
            separated=null;
            assertThrows(RuntimeException.class,()->{try(var ignored=RuntimeApplication.application(TestConfiguration.class).run("--server.port=0","--fs.require-separated-roles=true")){fail("legacy source admitted");}});
        }finally{
            separated=null;
            for(int i=roles.length-1;i>=0;i--){
                String group="a14_group_"+i,name="a14_login_"+i;
                for(String role:List.of(name,group))if(scalar("SELECT count(*) FROM pg_roles WHERE rolname='"+role+"'")>0){sql("DROP OWNED BY "+role);sql("DROP ROLE "+role);}
            }
        }
    }

}
