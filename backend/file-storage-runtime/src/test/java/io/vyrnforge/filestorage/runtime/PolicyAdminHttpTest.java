package io.vyrnforge.filestorage.runtime;

import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.filestorage.persistence.JdbcConfiguration;
import io.vyrnforge.filestorage.persistence.JdbcConfiguration.PolicyRule;
import io.vyrnforge.filestorage.persistence.JdbcPolicyControl;
import io.vyrnforge.filestorage.runtime.http.PolicyAdminHttpConfiguration;
import io.vyrnforge.filestorage.security.SecuredFiles.*;
import io.vyrnforge.filestorage.security.SecuredPolicyAdmin;
import io.vyrnforge.filestorage.security.SecuredPolicyAdmin.Action;
import io.vyrnforge.platform.kernel.context.*;
import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** End-to-end HTTP + disposable PostgreSQL; only identity/authority are synthetic. */
class PolicyAdminHttpTest {
    private static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");
    private static PGSimpleDataSource source;
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String ISS = "https://iam.example.test", AUD = "file-storage-test";
    private static UUID id(long n) { return new UUID(0x0190000000007000L, 0x8000000000000000L+n); }
    private static final TenantId A = new TenantId(id(1)), B = new TenantId(id(2));
    private static final UUID OSS = id(3), S3 = id(4), OL = id(5), SL = id(6);
    private static final ActorReference ADMIN = new ActorReference(ActorType.USER, "synthetic-admin", null, null);
    private static final Scope SCOPE = new Scope(A, "asset", "attachment");
    private static final String PATH = "/v1/admin/policies";
    private static final String QUERY = "?application=asset&purpose=attachment";

    @BeforeAll static void start() {
        DB.start();
        source = new PGSimpleDataSource();
        source.setURL(DB.getJdbcUrl());
        source.setUser(DB.getUsername());
        source.setPassword(DB.getPassword());
        RuntimeComposition.migrate(source);
    }

    @AfterAll static void stop() { DB.stop(); }

    @BeforeEach void seed() throws SQLException {
        sql("TRUNCATE fs.backend, fs.policy_revision, fs.file, fs_kernel.platform_audit_event, fs_kernel.platform_event_outbox CASCADE");
        sql("""
                INSERT INTO fs.backend VALUES (?, 'OSS', 'https://oss.example.test', 'test', 'AUTO'),
                    (?, 'S3', 'https://s3.example.test', 'test', 'PATH')
                """, OSS, S3);
        sql("""
                INSERT INTO fs.backend_revision VALUES (?, 1, 'ref:synthetic/oss', 'DRAFT', NULL),
                    (?, 1, 'ref:synthetic/s3', 'DRAFT', NULL)
                """, OSS, S3);
        sql("UPDATE fs.backend_revision SET validated_revision=config_revision");
        var config = new JdbcConfiguration(source, CLOCK);
        config.activateBackend(ADMIN, CorrelationContext.create(), new BackendId(OSS), 0, 1);
        config.activateBackend(ADMIN, CorrelationContext.create(), new BackendId(S3), 0, 1);
        sql("INSERT INTO fs.storage_location VALUES (?, ?, 'private-oss', 'private-prefix/'), (?, ?, 'private-s3', 'private-prefix/')",
                OL, OSS, SL, S3);
        config.publishPolicy(ADMIN, CorrelationContext.create(), SCOPE, 0,
                List.of(new PolicyRule(new LocationId(OL), 0, true)));
    }

    private static void sql(String statement, Object... args) throws SQLException {
        try (var c = source.getConnection(); var ps = c.prepareStatement(statement)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        }
    }

    private static long scalar(String query) throws SQLException {
        try (var c = source.getConnection(); var ps = c.prepareStatement(query); var rs = ps.executeQuery()) {
            rs.next(); return rs.getLong(1);
        }
    }

    static final class Authority {
        final Set<Action> actions = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final Set<LocationId> locations = java.util.concurrent.ConcurrentHashMap.newKeySet();
        volatile boolean member = true, entitled = true, unavailable = false;
        Authority() {
            actions.addAll(EnumSet.allOf(Action.class));
            locations.addAll(Set.of(new LocationId(OL), new LocationId(SL)));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(PolicyAdminHttpConfiguration.class)
    static class TestConfiguration {
        @Bean Authority authority() { return new Authority(); }

        @Bean SecuredPolicyAdmin policySecurity(Authority authority) {
            return new SecuredPolicyAdmin(new Trust(ISS, AUD), bearer -> {
                if (!Set.of("admin", "consumer").contains(bearer)) throw new IllegalArgumentException("private verifier detail");
                return new VerifiedAccess(ISS, bearer, Set.of(AUD), TokenKind.ACCESS, NOW, NOW.plusSeconds(300), NOW);
            }, (issuer, subject, audience, tenant) -> "admin".equals(subject) && A.equals(tenant)
                    ? Optional.of(ADMIN) : Optional.empty(),
                    (actor, tenant) -> authority.member,
                    (tenant, module) -> new ModuleEntitlement(A.value(), "file-storage", authority.entitled, NOW),
                    (actor, scope, action) -> {
                        if (authority.unavailable) throw new IllegalStateException("private authority detail");
                        return ADMIN.equals(actor) && SCOPE.equals(scope) && authority.actions.contains(action);
                    }, CLOCK);
        }

        @Bean JdbcPolicyControl policies(Authority authority) {
            return RuntimeComposition.policyControl(source, CLOCK, (actor, scope, locations) ->
                    ADMIN.equals(actor) && SCOPE.equals(scope) && authority.locations.containsAll(locations));
        }
    }

    private static ConfigurableApplicationContext application(String... extra) {
        var args = new ArrayList<>(List.of("--server.port=0"));
        args.addAll(List.of(extra));
        return RuntimeApplication.application(TestConfiguration.class).run(args.toArray(String[]::new));
    }

    private static HttpResponse<String> call(ConfigurableApplicationContext ctx, String method, String path,
            String bearer, TenantId tenant, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" +
                ctx.getEnvironment().getProperty("local.server.port") + PATH + path))
                .timeout(Duration.ofSeconds(10)).header("X-Tenant-Id", tenant.value().toString())
                .header("X-Correlation-Id", id(99).toString());
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static String publish(long expected, UUID location) {
        return """
                {"expectedRevision":%d,"rules":[{"locationId":"%s","priority":0,"eligible":true}]}
                """.formatted(expected, location);
    }

    private static void status(int expected, HttpResponse<String> response) {
        assertEquals(expected, response.statusCode(), response.body());
        assertEquals(id(99).toString(), response.headers().firstValue("X-Correlation-Id").orElseThrow());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().firstValue("X-Request-Id").isPresent());
    }

    @Test void policyLifecycleUsesRealCasHistoryAndAtomicAudit() throws Exception {
        try (var ctx = application()) {
            var active = call(ctx, "GET", "/active"+QUERY, "admin", A, null);
            status(200, active); assertTrue(active.body().contains(OL.toString()));
            for (String privateValue : List.of("private-oss", "private-prefix", "oss.example.test", "ref:synthetic"))
                assertFalse(active.body().contains(privateValue));
            status(200, call(ctx, "POST", "/revisions"+QUERY, "admin", A, publish(1, SL)));
            assertTrue(call(ctx, "GET", "/active"+QUERY, "admin", A, null).body().contains(SL.toString()));
            assertTrue(call(ctx, "GET", "/revisions/1"+QUERY, "admin", A, null).body().contains(OL.toString()));
            status(409, call(ctx, "POST", "/revisions"+QUERY, "admin", A, publish(1, OL)));
            status(200, call(ctx, "POST", "/rollback"+QUERY, "admin", A,
                    "{\"expectedRevision\":2,\"historicalRevision\":1}"));
            var history = call(ctx, "GET", "/revisions"+QUERY+"&limit=2", "admin", A, null);
            status(200, history); assertTrue(history.body().contains("[3,2]"), history.body());
            assertTrue(call(ctx, "GET", "/revisions"+QUERY+"&before=2&limit=2", "admin", A, null).body().contains("[1]"));
            assertEquals(3, scalar("SELECT policy_revision FROM fs.active_policy"));
            assertEquals(3, scalar("SELECT count(*) FROM fs.policy_revision"));
            assertEquals(1, scalar("SELECT count(*) FROM fs_kernel.platform_event_outbox WHERE event_type='file-storage.policy.published' AND payload_json->>'rollbackSourceRevision'='1'"));
            assertEquals(1, scalar("SELECT count(*) FROM fs_kernel.platform_audit_event WHERE action='file-storage.policy.published' AND changes_json->>'rollbackSourceRevision'='1'"));
        }
    }

    @Test void authenticationMembershipEntitlementAndActionChecksFailClosed() throws Exception {
        try (var ctx = application()) {
            var authority = ctx.getBean(Authority.class);
            var invalid = call(ctx, "GET", "/active"+QUERY, "bad", A, null);
            status(401, invalid); assertFalse(invalid.body().contains("private verifier detail"));
            status(403, call(ctx, "GET", "/active"+QUERY, "consumer", A, null));
            status(403, call(ctx, "GET", "/active"+QUERY, "admin", B, null));
            status(403, call(ctx, "GET", "/active?application=itsm&purpose=attachment", "admin", A, null));
            authority.actions.remove(Action.POLICY_PUBLISH);
            status(403, call(ctx, "POST", "/revisions"+QUERY, "admin", A, publish(1, SL)));
            status(200, call(ctx, "GET", "/active"+QUERY, "admin", A, null));
            authority.member = false;
            status(403, call(ctx, "GET", "/active"+QUERY, "admin", A, null));
            authority.member = true; authority.entitled = false;
            status(403, call(ctx, "GET", "/active"+QUERY, "admin", A, null));
            authority.entitled = true; authority.unavailable = true;
            var unavailable = call(ctx, "GET", "/active"+QUERY, "admin", A, null);
            status(403, unavailable); assertFalse(unavailable.body().contains("private authority detail"));
            assertEquals(1, scalar("SELECT count(*) FROM fs.policy_revision"));
        }
    }

    @Test void locationAuthorityAndRollbackPermissionRemainIndependent() throws Exception {
        try (var ctx = application()) {
            var authority = ctx.getBean(Authority.class);
            authority.locations.remove(new LocationId(SL));
            status(403, call(ctx, "POST", "/revisions"+QUERY, "admin", A, publish(1, SL)));
            String nonwinning = """
                    {"expectedRevision":1,"rules":[{"locationId":"%s","priority":0,"eligible":true},{"locationId":"%s","priority":1,"eligible":false}]}
                    """.formatted(OL, SL);
            status(403, call(ctx, "POST", "/revisions"+QUERY, "admin", A, nonwinning));
            authority.locations.add(new LocationId(SL));
            status(200, call(ctx, "POST", "/revisions"+QUERY, "admin", A, publish(1, SL)));
            authority.actions.remove(Action.POLICY_ROLLBACK);
            String rollback = "{\"expectedRevision\":2,\"historicalRevision\":1}";
            status(403, call(ctx, "POST", "/rollback"+QUERY, "admin", A, rollback));
            authority.actions.add(Action.POLICY_ROLLBACK);
            authority.locations.remove(new LocationId(OL));
            status(403, call(ctx, "GET", "/revisions/1"+QUERY, "admin", A, null));
            status(403, call(ctx, "POST", "/rollback"+QUERY, "admin", A, rollback));
            assertEquals(2, scalar("SELECT policy_revision FROM fs.active_policy"));
            assertEquals(2, scalar("SELECT count(*) FROM fs.policy_revision"));
        }
    }

    @Test void invalidContractsAndRoleIsolationDoNotWrite() throws Exception {
        try (var ctx = application()) {
            status(400, call(ctx, "GET", "/revisions"+QUERY+"&limit=101", "admin", A, null));
            status(400, call(ctx, "GET", "/active"+QUERY+"&application=itsm", "admin", A, null));
            status(400, call(ctx, "POST", "/revisions"+QUERY, "admin", A,
                    "{\"expectedRevision\":1,\"rules\":[{\"locationId\":\""+SL+"\",\"priority\":0}]}"));
            status(400, call(ctx, "POST", "/revisions"+QUERY, "admin", A,
                    publish(1, SL).replace("\"expectedRevision\"", "\"actor\":\"synthetic-admin\",\"expectedRevision\"")));
            status(404, call(ctx, "GET", "/revisions/99"+QUERY, "admin", A, null));
            assertEquals(1, scalar("SELECT count(*) FROM fs.policy_revision"));
        }
        assertThrows(RuntimeException.class, () -> {
            try (var ignored = application("--fs.role=TRANSFER")) { fail("admin routes must not start"); }
        });
        try (var ctx = RuntimeApplication.start("--server.port=0")) {
            assertEquals(404, call(ctx, "GET", "/active"+QUERY, "admin", A, null).statusCode());
        }
    }
}
