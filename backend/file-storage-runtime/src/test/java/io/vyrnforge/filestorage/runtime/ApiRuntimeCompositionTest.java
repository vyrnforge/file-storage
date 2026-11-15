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
                authority::ready);
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
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
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
}
