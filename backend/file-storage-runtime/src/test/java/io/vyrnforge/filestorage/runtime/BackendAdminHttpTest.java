package io.vyrnforge.filestorage.runtime;

import io.vyrnforge.filestorage.application.BackendValidator;
import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.filestorage.persistence.JdbcConfiguration;
import io.vyrnforge.filestorage.runtime.http.BackendAdminHttpConfiguration;
import io.vyrnforge.filestorage.security.SecuredBackendAdmin;
import io.vyrnforge.filestorage.security.SecuredBackendAdmin.Action;
import io.vyrnforge.filestorage.security.SecuredFiles.*;
import io.vyrnforge.platform.kernel.context.*;
import java.net.URI;
import java.net.http.*;
import java.sql.*;
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

/** Platform administration over real disposable PostgreSQL; validator and identity are synthetic. */
class BackendAdminHttpTest {
    private static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");
    private static PGSimpleDataSource source;
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String ISS = "https://iam.example.test", AUD = "file-storage-test";
    private static final ActorReference ADMIN = new ActorReference(ActorType.USER, "synthetic-platform-admin", null, null);
    private static final Target TARGET = new Target(Provider.S3, URI.create("https://s3.example.test"), "test", Addressing.PATH);
    private static final String PATH = "/v1/admin/backends";
    private static final UUID CORRELATION = UUID.fromString("01900000-0000-7000-8000-000000000099");
    private static final String REGISTER = """
            {"provider":"S3","endpoint":"https://s3.example.test","region":"test","addressing":"PATH","credentialRef":"ref:synthetic/v1"}
            """;
    private static final String LOCATION = """
            {"bucket":"private-bucket","prefix":"private-prefix"}
            """;

    @BeforeAll static void start() {
        DB.start();
        source = new PGSimpleDataSource();
        source.setURL(DB.getJdbcUrl());
        source.setUser(DB.getUsername());
        source.setPassword(DB.getPassword());
        RuntimeComposition.migrate(source);
    }

    @AfterAll static void stop() { DB.stop(); }

    @BeforeEach void reset() throws SQLException {
        try (var c = source.getConnection(); var st = c.createStatement()) {
            st.execute("TRUNCATE fs.backend, fs.policy_revision, fs.file, fs_kernel.platform_audit_event, fs_kernel.platform_event_outbox CASCADE");
        }
    }

    private static long scalar(String query) throws SQLException {
        try (var c = source.getConnection(); var st = c.createStatement(); var rs = st.executeQuery(query)) {
            rs.next(); return rs.getLong(1);
        }
    }

    static final class Authority {
        final Set<Action> actions = java.util.concurrent.ConcurrentHashMap.newKeySet();
        volatile BackendId restricted;
        volatile boolean unavailable, validReport = true;
        final java.util.concurrent.atomic.AtomicInteger probes = new java.util.concurrent.atomic.AtomicInteger();
        Authority() { actions.addAll(EnumSet.allOf(Action.class)); }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(BackendAdminHttpConfiguration.class)
    static class TestConfiguration {
        @Bean Authority authority() { return new Authority(); }

        @Bean SecuredBackendAdmin backendSecurity(Authority authority) {
            return new SecuredBackendAdmin(new Trust(ISS, AUD), bearer -> {
                if (!Set.of("platform", "tenant-admin").contains(bearer)) throw new IllegalArgumentException("private verifier detail");
                return new VerifiedAccess(ISS, bearer, Set.of(AUD), TokenKind.ACCESS, NOW, NOW.plusSeconds(300), NOW);
            }, (issuer, subject, audience) -> "platform".equals(subject) ? Optional.of(ADMIN) : Optional.empty(),
            (actor, action, backend) -> {
                if (authority.unavailable) throw new IllegalStateException("private authority detail");
                return ADMIN.equals(actor) && authority.actions.contains(action)
                        && (authority.restricted == null || authority.restricted.equals(backend));
            }, CLOCK);
        }

        @Bean BackendAdministration backendAdministration(Authority authority) {
            return new BackendAdministration(source, CLOCK, Set.of(TARGET), candidate -> {
                authority.probes.incrementAndGet();
                var checks = new HashMap<LocationId, Set<BackendValidator.Check>>();
                for (var location : candidate.locations()) {
                    checks.put(location.id(), authority.validReport
                            ? EnumSet.allOf(BackendValidator.Check.class) : Set.of(BackendValidator.Check.TLS));
                }
                return new BackendValidator.Report(candidate.backend(), candidate.revision(), checks);
            });
        }
    }

    private static ConfigurableApplicationContext application(String... extra) {
        var args = new ArrayList<>(List.of("--server.port=0"));
        args.addAll(List.of(extra));
        return RuntimeApplication.application(TestConfiguration.class).run(args.toArray(String[]::new));
    }

    private static HttpResponse<String> call(ConfigurableApplicationContext ctx, String method, String path,
            String bearer, String body, boolean tenantHeader) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" +
                ctx.getEnvironment().getProperty("local.server.port") + PATH + path))
                .timeout(Duration.ofSeconds(10)).header("X-Correlation-Id", CORRELATION.toString());
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (tenantHeader) request.header("X-Tenant-Id", "01900000-0000-7000-8000-000000000001");
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static HttpResponse<String> get(ConfigurableApplicationContext ctx, String path) throws Exception {
        return call(ctx, "GET", path, "platform", null, false);
    }

    private static HttpResponse<String> post(ConfigurableApplicationContext ctx, String path, String body) throws Exception {
        return call(ctx, "POST", path, "platform", body, false);
    }

    private static void status(int expected, HttpResponse<String> response) throws Exception {
        assertEquals(expected, response.statusCode(), response.body());
        OpenApiContract.assertResponse(response);
        assertEquals(CORRELATION.toString(), response.headers().firstValue("X-Correlation-Id").orElseThrow());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        for (String value : List.of("s3.example.test", "private-bucket", "private-prefix", "ref:synthetic", "private authority detail"))
            assertFalse(response.body().contains(value), response.body());
    }

    private static String field(String json, String name) {
        var match = Pattern.compile("\""+name+"\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        assertTrue(match.find(), json); return match.group(1);
    }

    private static String register(ConfigurableApplicationContext ctx) throws Exception {
        var response = post(ctx, "", REGISTER);
        status(200, response);
        return "/" + field(response.body(), "backendId");
    }

    private static String revision(long revision) { return "{\"revision\":"+revision+"}"; }
    private static String activate(long expected, long candidate) {
        return "{\"expectedRevision\":"+expected+",\"candidateRevision\":"+candidate+"}";
    }

    @Test void registryAndLifecycleUseRealTransactionsAndRedactedProjections() throws Exception {
        try (var ctx = application()) {
            String backend = register(ctx);
            status(200, post(ctx, backend+"/locations", LOCATION));
            status(200, get(ctx, "?limit=1"));
            status(200, get(ctx, backend+"/locations"));
            status(200, get(ctx, backend+"/revisions"));
            status(404, get(ctx, backend+"/active"));
            status(400, post(ctx, backend+"/activate", activate(0,1)));
            status(200, post(ctx, backend+"/validate", revision(1)));
            status(200, post(ctx, backend+"/activate", activate(0,1)));
            status(200, get(ctx, backend+"/active"));
            status(409, post(ctx, backend+"/locations", LOCATION.replace("private-prefix","other-prefix")));
            status(200, post(ctx, backend+"/credentials", "{\"expectedRevision\":1,\"credentialRef\":\"ref:synthetic/v2\"}"));
            status(409, post(ctx, backend+"/credentials", "{\"expectedRevision\":1,\"credentialRef\":\"ref:synthetic/v3\"}"));
            status(200, post(ctx, backend+"/validate", revision(2)));
            status(200, post(ctx, backend+"/activate", activate(1,2)));
            status(200, post(ctx, backend+"/drain", revision(2)));
            status(200, post(ctx, backend+"/retire", revision(1)));
            status(200, post(ctx, backend+"/retire", revision(2)));
            assertEquals(2, scalar("SELECT count(*) FROM fs.backend_revision WHERE status='RETIRED'"));
            assertEquals(2, scalar("SELECT count(*) FROM fs_kernel.platform_event_outbox WHERE event_type='file-storage.backend.retired'"));
            assertEquals(2, scalar("SELECT count(*) FROM fs_kernel.platform_audit_event WHERE action='file-storage.backend.retired'"));
        }
    }

    @Test void platformPermissionsAreIndependentAndExactBackendScoped() throws Exception {
        try (var ctx = application()) {
            status(401, call(ctx, "GET", "", "bad", null, false));
            status(403, call(ctx, "GET", "", "tenant-admin", null, false));
            status(400, call(ctx, "GET", "", "platform", null, true));
            String first = register(ctx), second = register(ctx);
            var authority = ctx.getBean(Authority.class);
            authority.restricted = new BackendId(UUID.fromString(first.substring(1)));
            status(403, get(ctx, ""));
            status(200, get(ctx, first+"/revisions"));
            status(403, get(ctx, second+"/revisions"));
            status(403, post(ctx, second+"/locations", LOCATION));
            authority.actions.remove(Action.REGISTER_LOCATION);
            status(403, post(ctx, first+"/locations", LOCATION));
            authority.unavailable = true;
            status(403, get(ctx, first+"/revisions"));
            assertEquals(0, scalar("SELECT count(*) FROM fs.storage_location"));
        }
    }

    @Test void validationCannotBeAssertedByRequestAndTargetPolicyIsServerOwned() throws Exception {
        try (var ctx = application()) {
            var authority = ctx.getBean(Authority.class);
            status(403, post(ctx, "", REGISTER.replace("s3.example.test", "unapproved.example.test")));
            status(400, post(ctx, "", REGISTER.replace("ref:synthetic/v1", "raw-secret")));
            assertEquals(0, scalar("SELECT count(*) FROM fs.backend"));
            String backend = register(ctx);
            status(200, post(ctx, backend+"/locations", LOCATION));
            status(400, post(ctx, backend+"/validate", "{\"revision\":1,\"validated\":true}"));
            assertEquals(0, authority.probes.get());
            authority.validReport = false;
            status(403, post(ctx, backend+"/validate", revision(1)));
            status(400, post(ctx, backend+"/activate", activate(0,1)));
            assertEquals(0, scalar("SELECT count(*) FROM fs.backend_revision WHERE validated_revision IS NOT NULL"));
            authority.validReport = true;
            status(200, post(ctx, backend+"/validate", revision(1)));
            assertEquals(2, authority.probes.get());
        }
    }

    @Test void retirementGuardAndRoleIsolationRemainEnforced() throws Exception {
        try (var ctx = application()) {
            String backend = register(ctx);
            var location = post(ctx, backend+"/locations", LOCATION);
            status(200, location);
            var locationId = new LocationId(UUID.fromString(field(location.body(), "locationId")));
            status(200, post(ctx, backend+"/validate", revision(1)));
            status(200, post(ctx, backend+"/activate", activate(0,1)));
            var tenant = new TenantId(UUID.fromString("01900000-0000-7000-8000-000000000001"));
            new JdbcConfiguration(source, CLOCK).publishPolicy(ADMIN, CorrelationContext.create(),
                    new Scope(tenant, "asset", "attachment"), 0,
                    List.of(new JdbcConfiguration.PolicyRule(locationId, 0, true)));
            status(200, post(ctx, backend+"/drain", revision(1)));
            status(409, post(ctx, backend+"/retire", revision(1)));
            assertEquals(1, scalar("SELECT count(*) FROM fs.backend_revision WHERE status='DRAINING'"));
            status(400, get(ctx, "?limit=101"));
        }
        assertThrows(RuntimeException.class, () -> {
            try (var ignored = application("--fs.role=WORKER")) { fail("backend routes must not start"); }
        });
        try (var ctx = RuntimeApplication.start("--server.port=0")) {
            assertEquals(404, get(ctx, "").statusCode());
        }
    }
}
