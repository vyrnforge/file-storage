package io.vyrnforge.filestorage.persistence;

import io.vyrnforge.filestorage.application.FileApplication;
import io.vyrnforge.filestorage.application.FileApplication.*;
import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.filestorage.security.SecuredFiles;
import io.vyrnforge.filestorage.security.SecuredFiles.*;
import io.vyrnforge.platform.kernel.context.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** Real disposable PostgreSQL; no production URL or credentials accepted. */
class JdbcTransactionsTest {
    private static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");
    private static PGSimpleDataSource dataSource;
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00.123456789Z");
    private static UUID id(long n) {
        return new UUID(0x0190000000007000L, 0x8000000000000000L + n);
    }
    private static final TenantId A = new TenantId(id(1));
    private static final TenantId B = new TenantId(id(2));
    private static final Caller ALICE = new Caller(A, "actor-a", "asset");
    private static final Caller BOB = new Caller(B, "actor-b", "asset");
    private static final Scope SCOPE = new Scope(A, "asset", "attachment");
    private static final UUID OSS = id(3), S3 = id(4), OL = id(5), SL = id(6);

    @BeforeAll
    static void start() throws SQLException {
        DB.start();
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(DB.getJdbcUrl());
        dataSource.setUser(DB.getUsername());
        dataSource.setPassword(DB.getPassword());
        // Exercise upgrade from V1 with real historical configuration.
        org.flywaydb.core.Flyway.configure().dataSource(dataSource)
                .schemas("fs").defaultSchema("fs").locations("classpath:db/filestorage")
                .target("1").load().migrate();
        sql("INSERT INTO fs.backend VALUES (?, 'OSS', 'https://oss.example.test', 'test', 'AUTO')", OSS);
        sql("INSERT INTO fs.backend_revision VALUES (?, 1, 'ref:synthetic/oss', 'ACTIVE', 1)", OSS);
        sql("INSERT INTO fs.storage_location VALUES (?, ?, 'oss-test', 'objects/')", OL, OSS);
        sql("INSERT INTO fs.policy_revision VALUES (?, 'asset', 'attachment', 1)", A.value());
        sql("INSERT INTO fs.policy_rule VALUES (?, 'asset', 'attachment', 1, ?, 0, true)", A.value(), OL);
        sql("INSERT INTO fs.active_policy VALUES (?, 'asset', 'attachment', 1)", A.value());
        StorageMigrations.migrate(dataSource);
        assertEquals(1, scalar("SELECT count(*) FROM fs.policy_revision WHERE sealed"));
        assertEquals(1, scalar("SELECT policy_revision FROM fs.active_policy"));
        assertEquals(1, scalar("SELECT config_revision FROM fs.backend_revision WHERE status='ACTIVE'"));
        assertEquals("23514", assertThrows(SQLException.class, () ->
                sql("UPDATE fs.policy_rule SET eligible=false")).getSQLState());
    }

    @AfterAll
    static void stop() {
        DB.stop();
    }

    @BeforeEach
    void seed() throws SQLException {
        sql("TRUNCATE fs.backend, fs.policy_revision, fs.file, fs.file_grant, fs_kernel.platform_idempotency, fs_kernel.platform_audit_event, fs_kernel.platform_event_outbox CASCADE");
        sql("""
                INSERT INTO fs.backend VALUES (?, 'OSS', 'https://oss.example.test', 'test', 'AUTO'),
                    (?, 'S3', 'https://s3.example.test', 'test', 'PATH')
                """, OSS, S3);
        sql("""
                INSERT INTO fs.backend_revision VALUES (?, 1, 'ref:synthetic/oss', 'DRAFT', NULL),
                    (?, 1, 'ref:synthetic/s3', 'DRAFT', NULL)
                """, OSS, S3);
        sql("UPDATE fs.backend_revision SET validated_revision=config_revision");
        configuration().activateBackend(ADMIN, CorrelationContext.create(), new BackendId(OSS), 0, 1);
        configuration().activateBackend(ADMIN, CorrelationContext.create(), new BackendId(S3), 0, 1);
        sql("INSERT INTO fs.storage_location VALUES (?, ?, 'oss-test', 'objects/'), (?, ?, 's3-test', 'objects/')",
                OL, OSS, SL, S3);
        configuration().publishPolicy(ADMIN, CorrelationContext.create(), SCOPE, 0, List.of(rule(OL, 0, true)));
    }

    private static final ActorReference ADMIN = new ActorReference(ActorType.USER, "synthetic-admin", null, null);

    private static JdbcConfiguration configuration() {
        return new JdbcConfiguration(dataSource, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static JdbcConfiguration.PolicyRule rule(UUID location, int priority, boolean eligible) {
        return new JdbcConfiguration.PolicyRule(new LocationId(location), priority, eligible);
    }

    // Deliberately invalid legacy/configuration fixtures; bypass the domain command,
    // but never mutate a sealed revision. Used to retain fail-closed reader coverage.
    private static void fixturePolicy(long revision, boolean eligible, boolean ambiguous) throws SQLException {
        sql("INSERT INTO fs.policy_revision (tenant_id, consumer, purpose, policy_revision) VALUES (?, 'asset', 'attachment', ?)", A.value(), revision);
        sql("INSERT INTO fs.policy_rule VALUES (?, 'asset', 'attachment', ?, ?, 0, ?)", A.value(), revision, OL, eligible);
        if (ambiguous) {
            sql("INSERT INTO fs.policy_rule VALUES (?, 'asset', 'attachment', ?, ?, 0, true)", A.value(), revision, SL);
        }
        sql("UPDATE fs.policy_revision SET sealed=true WHERE tenant_id=? AND policy_revision=?", A.value(), revision);
        sql("UPDATE fs.active_policy SET policy_revision=? WHERE tenant_id=?", revision, A.value());
    }

    private static long scalar(String query) throws SQLException {
        try (var connection = dataSource.getConnection();
             var st = connection.createStatement(); var rs = st.executeQuery(query)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void sql(String statement, Object... values) throws SQLException {
        try (var connection = dataSource.getConnection();
             var ps = connection.prepareStatement(statement)) {
            for (int i = 0; i < values.length; i++) {
                ps.setObject(i + 1, values[i]);
            }
            ps.executeUpdate();
        }
    }

    private static long count(String table) throws SQLException {
        // Only constant internal table names in tests.
        try (var connection = dataSource.getConnection();
             var st = connection.createStatement();
             var rs = st.executeQuery("SELECT count(*) FROM fs." + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static FileApplication application() {
        return new FileApplication(new JdbcTransactions(dataSource, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10)), new Authorization() {
            public boolean mayInitiate(Caller caller, String purpose) {
                return caller.equals(ALICE) || caller.equals(BOB);
            }
            public boolean mayAccess(Caller caller, Action action, Resource resource) {
                return caller.tenant().equals(resource.tenant())
                        && caller.actor().equals(resource.ownerActor())
                        && caller.consumer().equals(resource.ownerConsumer());
            }
        }, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5));
    }

    private static Initiate command(String key) {
        return new Initiate("attachment", "sample.txt", 5, "text/plain", key);
    }

    private static Entry entry(Unit unit, String key, long n) {
        var config = unit.configuration(SCOPE).orElseThrow();
        var placement = io.vyrnforge.filestorage.domain.StorageModel.select(SCOPE,
                config.policy(), config.locations(), config.backends(), config.eligible());
        var file = File.create(new FileId("synthetic:" + n), A, "sample.txt");
        var locator = new Locator(placement.location(), "objects/" + n, null);
        var version = Version.pending(file, new VersionId(id(n)), locator);
        var session = UploadSession.initiate(new SessionId(id(n + 1)), file, version.id(),
                SCOPE, placement, locator, NOW.plusSeconds(60), NOW);
        return new Entry(file, version, session, new RequestIdentity(ALICE, key), command(key));
    }

    @Test
    void kernelLeaseCommitsAndReplaysCurrentState() throws SQLException {
        var first = application().initiate(ALICE, command("kernel"));
        assertEquals(first, application().initiate(ALICE, command("kernel")));
        try (var c = dataSource.getConnection(); var s = c.createStatement();
             var rs = s.executeQuery("SELECT status, result_text FROM fs_kernel.platform_idempotency")) {
            assertTrue(rs.next());
            assertEquals("COMPLETED", rs.getString(1));
            assertEquals(first.session().value().toString(), rs.getString(2));
            assertFalse(rs.next());
        }
        assertEquals(1, count("file"));
    }

    @Test
    void kernelFailureAbortsLeaseAndRollsBack() throws SQLException {
        fixturePolicy(2, false, false);
        assertEquals(Code.NO_PLACEMENT, assertThrows(Failure.class,
                () -> application().initiate(ALICE, command("retry"))).code());
        assertEquals(0, count("file"));
        try (var c = dataSource.getConnection(); var s = c.createStatement();
             var rs = s.executeQuery("SELECT count(*) FROM fs_kernel.platform_idempotency")) {
            rs.next();
            assertEquals(0, rs.getLong(1));
        }
        configuration().publishPolicy(ADMIN, CorrelationContext.create(), SCOPE, 2, List.of(rule(OL, 0, true)));
        assertNotNull(application().initiate(ALICE, command("retry")));
        assertEquals(1, count("file"));
    }

    @Test
    void concurrentInitiationOneAggregate() throws Exception {
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CyclicBarrier(2);
            java.util.concurrent.Callable<Object> call = () -> {
                start.await(10, java.util.concurrent.TimeUnit.SECONDS);
                try {
                    return application().initiate(ALICE, command("concurrent"));
                } catch (Failure ex) {
                    return ex.code();
                }
            };
            var a = executor.submit(call);
            var b = executor.submit(call);
            var first = a.get(30, java.util.concurrent.TimeUnit.SECONDS);
            var second = b.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(first instanceof View || second instanceof View);
            var replay = application().initiate(ALICE, command("concurrent"));
            for (Object result : List.of(first, second)) {
                if (result instanceof View) {
                    assertEquals(replay, result);
                } else {
                    assertEquals(Code.IN_PROGRESS, result);
                }
            }
            assertEquals(1, count("file"));
            assertEquals(1, count("file_version"));
            assertEquals(1, count("upload_session"));
            assertEquals(1, count("initiation_request"));
        }
    }

    @Test
    void replayReauthorizesAndNeverRecreatesMissingAggregate() throws SQLException {
        var first = application().initiate(ALICE, command("guarded"));
        var denyRead = new FileApplication(
                new JdbcTransactions(dataSource, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10)),
                new Authorization() {
                    public boolean mayInitiate(Caller caller, String purpose) { return true; }
                    public boolean mayAccess(Caller caller, Action action, Resource resource) { return false; }
                }, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5));
        assertEquals(Code.DENIED, assertThrows(Failure.class,
                () -> denyRead.initiate(ALICE, command("guarded"))).code());
        sql("TRUNCATE fs.file CASCADE");
        assertEquals(Code.NOT_FOUND, assertThrows(Failure.class,
                () -> application().initiate(ALICE, command("guarded"))).code());
        assertEquals(0, count("file"));
        assertNotNull(first);
    }

    @Test
    void leaseCompletionFailureRollsBackAggregate() throws SQLException {
        var now = new java.util.concurrent.atomic.AtomicReference<>(NOW);
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
            public Instant instant() { return now.get(); }
        };
        var jdbc = new JdbcTransactions(dataSource, clock, Duration.ofSeconds(10));
        var identity = new RequestIdentity(ALICE, "late");
        assertThrows(JdbcTransactions.PersistenceFailure.class, () -> jdbc.initiate(
                identity, command("late"), unit -> {
                    Entry e = entry(unit, "late", 180);
                    unit.insert(e);
                    now.set(NOW.plusSeconds(11));
                    return new View(e.file().reference(), e.version().id(), e.file().state(),
                            e.version().state(), e.session().id(), e.session().state(), e.session().expires());
                }, unit -> { throw new AssertionError("unexpected replay"); }));
        assertEquals(0, count("file"));
        assertEquals(0, count("file_version"));
        assertEquals(0, count("upload_session"));
        assertEquals(0, count("initiation_request"));
        assertNotNull(application().initiate(ALICE, command("late")));
        assertEquals(1, count("file"));
    }

    @Test
    void expiredKernelLeaseDoesNotCreateDuplicate() throws SQLException {
        var first = application().initiate(ALICE, command("retained"));
        sql("UPDATE fs_kernel.platform_idempotency SET expires_at = now() - interval '100 years'");
        assertEquals(first, application().initiate(ALICE, command("retained")));
        assertEquals(1, count("file"));
    }

    @Test
    void p01_migrationIsRepeatable() throws SQLException {
        StorageMigrations.migrate(dataSource);
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT count(*) FROM fs.flyway_schema_history WHERE version = '1' AND success")) {
            rs.next();
            assertEquals(1, rs.getLong(1));
        }
        assertEquals(2, count("backend"));
    }

    @Test
    void p02_persistsAndRehydratesAcrossConnections() throws SQLException {
        View created = application().initiate(ALICE, command("one"));
        assertEquals(1, count("file"));
        assertEquals(1, count("file_version"));
        assertEquals(1, count("upload_session"));
        assertEquals(1, count("initiation_request"));
        var reopened = application();
        assertEquals(created, reopened.session(ALICE, created.session()));
        assertEquals(created, reopened.initiate(ALICE, command("one")));
        assertEquals(NOW.plusSeconds(300), created.expiresAt());
        var metadata = reopened.metadata(ALICE, new FileId(created.file().fileId()));
        assertEquals(created.file(), metadata.file());
        assertEquals("sample.txt", metadata.name());
        assertEquals(5, metadata.declaredSize());
        var stored = new JdbcTransactions(dataSource, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10)).execute(
                unit -> unit.bySession(A, created.session()).orElseThrow());
        assertEquals(OL, stored.session().placement().location().value());
        assertEquals(stored.version().locator(), stored.session().expectedObject());
    }

    private static void switchPolicy() throws SQLException {
        configuration().publishPolicy(ADMIN, CorrelationContext.create(), SCOPE, 1, List.of(rule(SL, 0, true)));
        sql("UPDATE fs.backend_revision SET status='DRAINING' WHERE backend_id=?", OSS);
    }

    @Test
    void p03_policySwitchPinsPreviousSession() throws SQLException {
        var first = application().initiate(ALICE, command("old"));
        switchPolicy();
        var second = application().initiate(ALICE, command("new"));
        var jdbc = new JdbcTransactions(dataSource, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10));
        var old = jdbc.execute(u -> u.bySession(A, first.session()).orElseThrow());
        var next = jdbc.execute(u -> u.bySession(A, second.session()).orElseThrow());
        assertEquals(OL, old.session().placement().location().value());
        assertEquals(1, old.session().placement().policyRevision());
        assertEquals(SL, next.session().placement().location().value());
        assertEquals(2, next.session().placement().policyRevision());
        assertEquals(first, application().initiate(ALICE, command("old")));
    }

    @Test
    void p04_tenantQueriesAndForeignKeys() throws SQLException {
        var first = application().initiate(ALICE, command("one"));
        var file = new FileId(first.file().fileId());
        var jdbc = new JdbcTransactions(dataSource, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10));
        assertTrue(jdbc.execute(u -> u.byFile(B, file)).isEmpty());
        assertTrue(jdbc.execute(u -> u.bySession(B, first.session())).isEmpty());
        assertEquals(Code.NOT_FOUND, assertThrows(Failure.class,
                () -> application().metadata(BOB, file)).code());
        assertEquals("23503", assertThrows(SQLException.class, () -> sql("""
                INSERT INTO fs.file_version VALUES (?, ?, ?, ?, 'objects/foreign', 'PENDING')
                """, id(60), B.value(), file.value(), OL)).getSQLState());
        assertEquals("23503", assertThrows(SQLException.class, () -> sql("""
                INSERT INTO fs.file_grant (grant_id, tenant_id, actor, consumer, purpose, action, scope,
                    file_id, not_before, expires_at)
                VALUES (?, ?, 'actor-b', 'asset', 'attachment', 'READ_METADATA', 'EXACT_FILE',
                    ?, now(), now()+interval '1 hour')
                """, id(61), B.value(), file.value())).getSQLState());
    }

    @Test
    void p05_rollbackAndDuplicateIdentity() throws SQLException {
        var jdbc = new JdbcTransactions(dataSource, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10));
        assertThrows(IllegalStateException.class, () -> jdbc.execute(unit -> {
            unit.insert(entry(unit, "rollback", 100));
            throw new IllegalStateException("synthetic callback failure");
        }));
        assertEquals(0, count("file"));
        assertEquals(0, count("file_version"));
        assertEquals(0, count("upload_session"));
        assertEquals(0, count("initiation_request"));
        var first = application().initiate(ALICE, command("one"));
        assertEquals(Code.CONFLICT, assertThrows(Failure.class, () -> jdbc.execute(unit -> {
            unit.insert(entry(unit, "one", 110));
            return null;
        })).code());
        assertEquals(1, count("file"));
        assertEquals(1, count("file_version"));
        assertEquals(1, count("upload_session"));
        assertEquals(1, count("initiation_request"));
        assertEquals(Code.CONFLICT, assertThrows(Failure.class, () -> application().initiate(ALICE,
                new Initiate("attachment", "different.txt", 5, "text/plain", "one"))).code());
        assertEquals(first, application().initiate(ALICE, command("one")));
    }

    @Test
    void p06_missingIneligibleAndAmbiguousFailClosed() throws SQLException {
        assertEquals(Code.NO_PLACEMENT, assertThrows(Failure.class,
                () -> application().initiate(BOB, command("other"))).code());
        fixturePolicy(2, false, false);
        assertEquals(Code.NO_PLACEMENT, assertThrows(Failure.class,
                () -> application().initiate(ALICE, command("one"))).code());
        fixturePolicy(3, true, true);
        assertEquals(Code.NO_PLACEMENT, assertThrows(Failure.class,
                () -> application().initiate(ALICE, command("one"))).code());
        assertEquals(0, count("file"));
    }

    @Test
    void consistentSnapshotAndClosedUnit() {
        var jdbc = new JdbcTransactions(dataSource, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10));
        Unit escaped = jdbc.execute(unit -> {
            assertEquals(1, unit.configuration(SCOPE).orElseThrow().policy().revision());
            try {
                switchPolicy();
            } catch (SQLException ex) {
                throw new AssertionError(ex);
            }
            assertEquals(1, unit.configuration(SCOPE).orElseThrow().policy().revision());
            return unit;
        });
        assertThrows(JdbcTransactions.PersistenceFailure.class, () -> escaped.configuration(SCOPE));
        long revision = jdbc.execute(u -> u.configuration(SCOPE).orElseThrow().policy().revision());
        assertEquals(2, revision);
    }

    private static void draft(long revision, boolean validated) throws SQLException {
        sql("INSERT INTO fs.backend_revision VALUES (?, ?, 'ref:synthetic/rotated', 'DRAFT', NULL)", OSS, revision);
        if (validated) {
            sql("UPDATE fs.backend_revision SET validated_revision=config_revision WHERE backend_id=? AND config_revision=?", OSS, revision);
        }
    }

    @Test
    void backendActivationRequiresExactValidationAndPreservesPins() throws SQLException {
        var first = application().initiate(ALICE, command("before"));
        draft(2, false);
        assertEquals(Code.INVALID, assertThrows(Failure.class,
                () -> configuration().activateBackend(ADMIN, CorrelationContext.create(), new BackendId(OSS), 1, 2)).code());
        assertEquals(1, scalar("SELECT config_revision FROM fs.backend_revision WHERE backend_id='" + OSS + "' AND status='ACTIVE'"));
        assertEquals("23514", assertThrows(SQLException.class, () -> sql(
                "UPDATE fs.backend_revision SET validated_revision=1 WHERE backend_id=? AND config_revision=2", OSS)).getSQLState());
        sql("UPDATE fs.backend_revision SET validated_revision=2 WHERE backend_id=? AND config_revision=2", OSS);
        assertEquals(2, configuration().activateBackend(ADMIN, CorrelationContext.create(), new BackendId(OSS), 1, 2));
        assertEquals(Code.CONFLICT, assertThrows(Failure.class,
                () -> configuration().activateBackend(ADMIN, CorrelationContext.create(), new BackendId(OSS), 1, 2)).code());
        var second = application().initiate(ALICE, command("after"));
        var jdbc = new JdbcTransactions(dataSource, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10));
        assertEquals(1, jdbc.execute(u -> u.bySession(A, first.session()).orElseThrow()).session().placement().configRevision());
        assertEquals(2, jdbc.execute(u -> u.bySession(A, second.session()).orElseThrow()).session().placement().configRevision());
        assertEquals(1, scalar("SELECT count(*) FROM fs.backend_revision WHERE backend_id='" + OSS + "' AND status='DRAINING'"));
        assertEquals(first, application().initiate(ALICE, command("before")));
        assertEquals("23514", assertThrows(SQLException.class, () -> sql(
                "UPDATE fs.backend_revision SET credential_ref='ref:synthetic/changed' WHERE backend_id=? AND config_revision=1", OSS)).getSQLState());
    }

    private static List<Object> competing(java.util.concurrent.Callable<Long> left,
                                          java.util.concurrent.Callable<Long> right) throws Exception {
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var barrier = new java.util.concurrent.CyclicBarrier(2);
            java.util.function.Function<java.util.concurrent.Callable<Long>, java.util.concurrent.Callable<Object>> wrap =
                    call -> () -> {
                        barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        try {
                            return call.call();
                        } catch (Failure ex) {
                            return ex.code();
                        }
                    };
            var a = executor.submit(wrap.apply(left));
            var b = executor.submit(wrap.apply(right));
            return List.of(a.get(30, java.util.concurrent.TimeUnit.SECONDS),
                    b.get(30, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test
    void backendActivationFailureRollsBackDrain() throws SQLException {
        draft(2, true);
        sql("""
                CREATE FUNCTION fs.test_reject_activation() RETURNS trigger LANGUAGE plpgsql AS $test$
                BEGIN
                    IF NEW.config_revision = 2 AND NEW.status = 'ACTIVE' THEN
                        RAISE EXCEPTION 'synthetic activation failure' USING ERRCODE = '23514';
                    END IF;
                    RETURN NEW;
                END;
                $test$
                """);
        try {
            sql("""
                    CREATE TRIGGER test_reject_activation BEFORE UPDATE ON fs.backend_revision
                    FOR EACH ROW EXECUTE FUNCTION fs.test_reject_activation()
                    """);
            assertEquals(Code.INVALID, assertThrows(Failure.class,
                    () -> configuration().activateBackend(ADMIN, CorrelationContext.create(), new BackendId(OSS), 1, 2)).code());
            assertEquals(1, scalar("SELECT config_revision FROM fs.backend_revision WHERE backend_id='" + OSS + "' AND status='ACTIVE'"));
            assertEquals(1, scalar("SELECT count(*) FROM fs.backend_revision WHERE backend_id='" + OSS + "' AND status='DRAFT'"));
            assertEquals(0, scalar("SELECT count(*) FROM fs.backend_revision WHERE backend_id='" + OSS + "' AND status='DRAINING'"));
        } finally {
            sql("DROP TRIGGER IF EXISTS test_reject_activation ON fs.backend_revision");
            sql("DROP FUNCTION fs.test_reject_activation()");
        }
        assertEquals(2, configuration().activateBackend(ADMIN, CorrelationContext.create(), new BackendId(OSS), 1, 2));
    }

    @Test
    void concurrentBackendActivationHasOneWinner() throws Exception {
        draft(2, true);
        draft(3, true);
        var results = competing(() -> configuration().activateBackend(ADMIN, CorrelationContext.create(), new BackendId(OSS), 1, 2),
                () -> configuration().activateBackend(ADMIN, CorrelationContext.create(), new BackendId(OSS), 1, 3));
        assertEquals(1, results.stream().filter(r -> r instanceof Long).count());
        assertEquals(1, results.stream().filter(r -> r == Code.CONFLICT).count());
        long winner = (Long) results.stream().filter(r -> r instanceof Long).findFirst().orElseThrow();
        assertEquals(winner, scalar("SELECT config_revision FROM fs.backend_revision WHERE backend_id='" + OSS + "' AND status='ACTIVE'"));
        assertEquals(1, scalar("SELECT count(*) FROM fs.backend_revision WHERE backend_id='" + OSS + "' AND status='DRAFT'"));
    }

    @Test
    void concurrentPolicyPublicationHasOneWinner() throws Exception {
        var results = competing(() -> configuration().publishPolicy(ADMIN, CorrelationContext.create(), SCOPE, 1, List.of(rule(OL, 0, true))),
                () -> configuration().publishPolicy(ADMIN, CorrelationContext.create(), SCOPE, 1, List.of(rule(SL, 0, true))));
        assertEquals(1, results.stream().filter(r -> r.equals(2L)).count());
        assertEquals(1, results.stream().filter(r -> r == Code.CONFLICT).count());
        assertEquals(2, count("policy_revision"));
        assertEquals(2, scalar("SELECT policy_revision FROM fs.active_policy"));
        assertEquals(2, count("policy_rule"));
        var initial = new Scope(B, "asset", "attachment");
        var first = competing(() -> configuration().publishPolicy(ADMIN, CorrelationContext.create(), initial, 0, List.of(rule(OL, 0, true))),
                () -> configuration().publishPolicy(ADMIN, CorrelationContext.create(), initial, 0, List.of(rule(SL, 0, true))));
        assertEquals(1, first.stream().filter(r -> r.equals(1L)).count());
        assertEquals(1, first.stream().filter(r -> r == Code.CONFLICT).count());
        assertEquals(3, count("policy_revision"));
    }

    @Test
    void invalidPolicyRollsBackAndPublishedHistoryIsImmutable() throws SQLException {
        var config = configuration();
        assertEquals(Code.NO_PLACEMENT, assertThrows(Failure.class,
                () -> config.publishPolicy(ADMIN, CorrelationContext.create(), SCOPE, 1, List.of(rule(OL, 0, false), rule(SL, 1, true)))).code());
        assertEquals(Code.NO_PLACEMENT, assertThrows(Failure.class,
                () -> config.publishPolicy(ADMIN, CorrelationContext.create(), SCOPE, 1, List.of(rule(OL, 0, true), rule(SL, 0, true)))).code());
        assertEquals(Code.INVALID, assertThrows(Failure.class,
                () -> config.publishPolicy(ADMIN, CorrelationContext.create(), SCOPE, 1, List.of(rule(id(99), 0, true)))).code());
        assertEquals(1, count("policy_revision"));
        assertEquals(1, count("policy_rule"));
        assertEquals(1, scalar("SELECT policy_revision FROM fs.active_policy"));
        assertEquals(2, config.publishPolicy(ADMIN, CorrelationContext.create(), SCOPE, 1, List.of(rule(SL, 0, true))));
        assertEquals("23514", assertThrows(SQLException.class, () -> sql(
                "UPDATE fs.policy_rule SET priority=5 WHERE policy_revision=1")).getSQLState());
        assertEquals("23514", assertThrows(SQLException.class, () -> sql(
                "INSERT INTO fs.policy_rule VALUES (?, 'asset', 'attachment', 1, ?, 1, true)", A.value(), SL)).getSQLState());
        assertEquals("23514", assertThrows(SQLException.class, () -> sql(
                "DELETE FROM fs.policy_rule WHERE policy_revision=1")).getSQLState());
        assertEquals("23514", assertThrows(SQLException.class, () -> sql(
                "UPDATE fs.policy_revision SET sealed=false WHERE policy_revision=1")).getSQLState());
        assertEquals("23514", assertThrows(SQLException.class, () -> sql(
                "UPDATE fs.active_policy SET policy_revision=1")).getSQLState());
        assertEquals(3, config.publishPolicy(ADMIN, CorrelationContext.create(), SCOPE, 2, List.of(rule(OL, 0, true))));
    }

    private static final Caller ITSM = new Caller(A, "actor-a", "itsm");
    private static final Caller SAME_TENANT = new Caller(A, "actor-b", "asset");

    private static JdbcGrants grants() {
        return new JdbcGrants(dataSource, Clock.fixed(NOW, ZoneOffset.UTC), 100, 5);
    }

    private static SecuredFiles secured(Grants source) {
        String issuer = "https://iam.example.test";
        String audience = "file-storage-test";
        Map<String, Caller> callers = Map.of("alice", ALICE, "bob", SAME_TENANT, "itsm", ITSM,
                "other-tenant", BOB);
        return new SecuredFiles(new Trust(issuer, audience),
                bearer -> {
                    if (!callers.containsKey(bearer)) throw new IllegalArgumentException("invalid synthetic token");
                    return new VerifiedAccess(issuer, bearer, Set.of(audience), TokenKind.ACCESS,
                            NOW.minusSeconds(1), NOW.plusSeconds(300), NOW);
                },
                (iss, subject, aud) -> Optional.ofNullable(callers.get(subject)).map(c ->
                        new Binding(new ActorReference(ActorType.USER, c.actor(), null, null),
                                c.tenant(), c.consumer(), Set.of(Action.values()))),
                (actor, tenant) -> true,
                (tenant, module) -> new ModuleEntitlement(tenant, module, true, NOW),
                source, new JdbcTransactions(dataSource, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10)),
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5));
    }

    private static UUID grant(Caller caller, Action action, String purpose, GrantScope scope, FileId file,
                              Instant before, Instant expires, boolean revoked) throws SQLException {
        UUID id = UUID.randomUUID();
        sql("""
                INSERT INTO fs.file_grant (grant_id, tenant_id, actor, consumer, purpose, action, scope,
                    file_id, not_before, expires_at, revoked)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, caller.tenant().value(), caller.actor(), caller.consumer(), purpose,
                action.name(), scope.name(), file == null ? null : file.value(),
                before.atOffset(ZoneOffset.UTC), expires.atOffset(ZoneOffset.UTC), revoked);
        return id;
    }

    private static UUID grant(Caller caller, Action action, GrantScope scope, FileId file) throws SQLException {
        return grant(caller, action, "attachment", scope, file, NOW.minusSeconds(1), NOW.plusSeconds(300), false);
    }

    private static View securedInitiation(SecuredFiles facade, String key) throws SQLException {
        grant(ALICE, Action.INITIATE, GrantScope.INITIATE_PURPOSE, null);
        return facade.initiate("alice", A, null, command(key)).value();
    }

    @Test
    void durableGrantsAuthorizeOnlyExactActorAppPurposeAndAction() throws SQLException {
        var facade = secured(grants());
        assertEquals(SecurityCode.ACCESS_DENIED, assertThrows(SecurityFailure.class,
                () -> facade.initiate("alice", A, null, command("denied"))).code());
        assertEquals(0, count("file"));
        var first = securedInitiation(facade, "secured");
        var file = new FileId(first.file().fileId());
        grant(ALICE, Action.READ_METADATA, GrantScope.OWNED_FILE, null);
        grant(SAME_TENANT, Action.READ_METADATA, GrantScope.OWNED_FILE, null);
        grant(ITSM, Action.READ_METADATA, GrantScope.OWNED_FILE, null);
        grant(BOB, Action.READ_METADATA, GrantScope.OWNED_FILE, null);
        assertEquals(first.file(), facade.metadata("alice", A, null, file).value().file());
        assertEquals(Code.NOT_FOUND, assertThrows(Failure.class,
                () -> facade.metadata("bob", A, null, file)).code());
        assertEquals(Code.NOT_FOUND, assertThrows(Failure.class,
                () -> facade.metadata("itsm", A, null, file)).code());
        assertEquals(Code.NOT_FOUND, assertThrows(Failure.class,
                () -> facade.metadata("other-tenant", B, null, file)).code());
        assertEquals(SecurityCode.ACCESS_DENIED, assertThrows(SecurityFailure.class,
                () -> facade.metadata("alice", B, null, file)).code());
        grant(ITSM, Action.READ_METADATA, "other-purpose", GrantScope.EXACT_FILE, file,
                NOW.minusSeconds(1), NOW.plusSeconds(60), false);
        assertEquals(Code.NOT_FOUND, assertThrows(Failure.class,
                () -> facade.metadata("itsm", A, null, file)).code());
        grant(ITSM, Action.READ_METADATA, GrantScope.EXACT_FILE, file);
        assertEquals(first.file(), facade.metadata("itsm", A, null, file).value().file());
        assertEquals(Code.NOT_FOUND, assertThrows(Failure.class,
                () -> facade.session("itsm", A, null, first.session())).code());
        grant(ITSM, Action.READ_SESSION, GrantScope.EXACT_FILE, file);
        assertEquals(first.session(), facade.session("itsm", A, null, first.session()).value().session());
        assertEquals(1, count("file"));
    }

    @Test
    void revokedGrantDeniesReplayWithoutRecreatingAggregate() throws SQLException {
        var facade = secured(grants());
        var first = securedInitiation(facade, "replay-grant");
        UUID read = grant(ALICE, Action.READ_SESSION, GrantScope.OWNED_FILE, null);
        assertEquals(first, facade.initiate("alice", A, null, command("replay-grant")).value());
        sql("UPDATE fs.file_grant SET revoked=true WHERE grant_id=?", read);
        assertEquals(Code.NOT_FOUND, assertThrows(Failure.class,
                () -> facade.session("alice", A, null, first.session())).code());
        assertEquals(Code.DENIED, assertThrows(Failure.class,
                () -> facade.initiate("alice", A, null, command("replay-grant"))).code());
        assertEquals(1, count("file"));
        assertEquals(1, count("initiation_request"));
        assertEquals(1, scalar("SELECT count(*) FROM fs_kernel.platform_idempotency WHERE status='COMPLETED'"));
        sql("UPDATE fs.file_grant SET revoked=true WHERE action='INITIATE'");
        assertEquals(SecurityCode.ACCESS_DENIED, assertThrows(SecurityFailure.class,
                () -> facade.initiate("alice", A, null, command("replay-grant"))).code());
    }

    @Test
    void grantValidityUsesInclusiveStartExclusiveEnd() throws SQLException {
        Instant boundary = NOW.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        grant(ALICE, Action.INITIATE, "attachment", GrantScope.INITIATE_PURPOSE, null,
                boundary.minusSeconds(1), boundary, false);
        grant(ALICE, Action.INITIATE, "attachment", GrantScope.INITIATE_PURPOSE, null,
                boundary.plusNanos(1000), boundary.plusSeconds(1), false);
        grant(ALICE, Action.INITIATE, "attachment", GrantScope.INITIATE_PURPOSE, null,
                boundary.minusSeconds(1), boundary.plusSeconds(1), true);
        assertTrue(grants().current(ALICE).isEmpty());
        grant(ALICE, Action.INITIATE, "attachment", GrantScope.INITIATE_PURPOSE, null,
                boundary, boundary.plusNanos(1000), false);
        assertEquals(1, grants().current(ALICE).size());
        assertNotNull(secured(grants()).initiate("alice", A, null, command("boundary")));
    }

    @Test
    void grantReadsObserveCommittedRevocationOutsideMetadataSnapshot() throws SQLException {
        UUID grantId = grant(ALICE, Action.INITIATE, GrantScope.INITIATE_PURPOSE, null);
        var source = grants();
        var jdbc = new JdbcTransactions(dataSource, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10));
        jdbc.execute(unit -> {
            assertTrue(unit.configuration(SCOPE).isPresent());
            assertEquals(1, source.current(ALICE).size());
            try (var c = dataSource.getConnection(); var ps = c.prepareStatement(
                    "UPDATE fs.file_grant SET revoked=true WHERE grant_id=?")) {
                c.setAutoCommit(false);
                ps.setObject(1, grantId);
                ps.executeUpdate();
                assertEquals(1, source.current(ALICE).size());
                c.commit();
            } catch (SQLException ex) {
                throw new AssertionError(ex);
            }
            assertTrue(source.current(ALICE).isEmpty());
            return null;
        });
    }

    @Test
    void grantQueryFailureAndOverflowFailClosed() throws SQLException {
        var facade = secured(grants());
        var first = securedInitiation(facade, "outage");
        var file = new FileId(first.file().fileId());
        grant(ALICE, Action.READ_METADATA, GrantScope.OWNED_FILE, null);
        var bounded = new JdbcGrants(dataSource, Clock.fixed(NOW, ZoneOffset.UTC), 1, 5);
        assertEquals("GRANT_SOURCE_UNAVAILABLE", assertThrows(JdbcGrants.GrantSourceUnavailable.class,
                () -> bounded.current(ALICE)).getMessage());
        assertEquals(SecurityCode.ACCESS_DENIED, assertThrows(SecurityFailure.class,
                () -> secured(bounded).initiate("alice", A, null, command("overflow"))).code());
        sql("ALTER TABLE fs.file_grant RENAME TO unavailable_grant");
        try {
            assertEquals("GRANT_SOURCE_UNAVAILABLE", assertThrows(JdbcGrants.GrantSourceUnavailable.class,
                    () -> grants().current(ALICE)).getMessage());
            assertEquals(SecurityCode.ACCESS_DENIED, assertThrows(SecurityFailure.class,
                    () -> facade.initiate("alice", A, null, command("outage-new"))).code());
            assertEquals(Code.NOT_FOUND, assertThrows(Failure.class,
                    () -> facade.metadata("alice", A, null, file)).code());
        } finally {
            sql("ALTER TABLE fs.unavailable_grant RENAME TO file_grant");
        }
        assertEquals(1, count("file"));
        assertEquals(first.file(), facade.metadata("alice", A, null, file).value().file());
    }

    @Test
    void grantQueryIsExactAndParameterized() throws SQLException {
        grant(ALICE, Action.INITIATE, GrantScope.INITIATE_PURPOSE, null);
        grant(SAME_TENANT, Action.READ_METADATA, GrantScope.OWNED_FILE, null);
        grant(ITSM, Action.READ_METADATA, GrantScope.OWNED_FILE, null);
        grant(BOB, Action.READ_METADATA, GrantScope.OWNED_FILE, null);
        assertEquals(1, grants().current(ALICE).size());
        assertEquals(Action.INITIATE, grants().current(ALICE).getFirst().action());
        assertTrue(grants().current(new Caller(A, "actor-a' OR '1'='1", "asset")).isEmpty());
        assertTrue(grants().current(new Caller(A, "actor-a", "asset' OR '1'='1")).isEmpty());
        assertTrue(grants().current(new Caller(B, "actor-a", "asset")).isEmpty());
    }

    private static long intents(String table, String column, String type) throws SQLException {
        // Constants supplied by test methods only.
        return scalar("SELECT count(*) FROM fs_kernel." + table + " WHERE " + column + "='" + type + "'");
    }

    @Test
    void initiationIntentsHaveTrustedCorrelationAndMinimalPayload() throws SQLException {
        var facade = secured(grants());
        grant(ALICE, Action.INITIATE, GrantScope.INITIATE_PURPOSE, null);
        grant(ALICE, Action.READ_SESSION, GrantScope.OWNED_FILE, null);
        UUID correlation = id(901);
        var command = new Initiate("attachment", "private-name.txt", 5, "text/plain", "private-request");
        var first = facade.initiate("alice", A, correlation, command);
        assertEquals(1, intents("platform_event_outbox", "event_type", "file-storage.upload.initiated"));
        assertEquals(1, intents("platform_audit_event", "action", "file-storage.upload.initiated"));
        try (var c = dataSource.getConnection(); var ps = c.prepareStatement("""
                SELECT e.subject_id, e.tenant_id, e.actor_id, e.correlation_id,
                       e.event_version, e.status, e.payload_json::text,
                       a.changes_json->>'requestId', a.outcome, a.actor_id, a.correlation_id
                FROM fs_kernel.platform_event_outbox e JOIN fs_kernel.platform_audit_event a
                  ON a.resource_id=e.subject_id AND a.action=e.event_type
                WHERE e.event_type='file-storage.upload.initiated'
                """); var rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(first.value().file().fileId(), rs.getString(1));
            assertEquals(A.value(), rs.getObject(2, UUID.class));
            assertEquals(ALICE.actor(), rs.getString(3));
            assertEquals(correlation, rs.getObject(4, UUID.class));
            assertEquals(1, rs.getInt(5));
            assertEquals("PENDING", rs.getString(6));
            String payload = rs.getString(7);
            assertTrue(payload.contains(first.value().session().value().toString()));
            assertTrue(payload.contains(first.value().version().value().toString()));
            for (String forbidden : List.of("private-name", "private-request", "text/plain", "objects/", "ref:", "oss-test")) {
                assertFalse(payload.contains(forbidden));
            }
            assertEquals(first.correlation().requestId().toString(), rs.getString(8));
            assertEquals("SUCCESS", rs.getString(9));
            assertEquals(ALICE.actor(), rs.getString(10));
            assertEquals(correlation, rs.getObject(11, UUID.class));
            assertFalse(rs.next());
        }
        assertEquals(first.value(), facade.initiate("alice", A, id(902), command).value());
        sql("UPDATE fs_kernel.platform_idempotency SET expires_at=now()-interval '100 years'");
        assertEquals(first.value(), facade.initiate("alice", A, id(903), command).value());
        assertEquals(1, intents("platform_event_outbox", "event_type", "file-storage.upload.initiated"));
        assertEquals(1, intents("platform_audit_event", "action", "file-storage.upload.initiated"));
        assertEquals(1, count("file"));
        assertEquals(1, scalar("SELECT count(*) FROM fs_kernel.platform_idempotency WHERE status='COMPLETED'"));
    }

    @Test
    void configIntentsAreAtomicAndAuditIsAppendOnly() throws SQLException {
        draft(2, true);
        UUID correlation = id(904);
        var ctx = CorrelationContext.create(correlation);
        assertEquals(2, configuration().activateBackend(ADMIN, ctx, new BackendId(OSS), 1, 2));
        assertEquals(2, configuration().publishPolicy(ADMIN, ctx, SCOPE, 1, List.of(rule(SL, 0, true))));
        assertEquals(2, scalar("SELECT count(*) FROM fs_kernel.platform_event_outbox WHERE correlation_id='" + correlation + "'"));
        assertEquals(2, scalar("SELECT count(*) FROM fs_kernel.platform_audit_event WHERE correlation_id='" + correlation + "'"));
        assertEquals(1, scalar("SELECT count(*) FROM fs_kernel.platform_event_outbox WHERE correlation_id='" + correlation + "' AND tenant_id IS NULL AND event_type='file-storage.backend.activated'"));
        assertEquals(1, scalar("SELECT count(*) FROM fs_kernel.platform_event_outbox WHERE correlation_id='" + correlation + "' AND tenant_id='" + A.value() + "' AND event_type='file-storage.policy.published'"));
        assertEquals(0, scalar("SELECT count(*) FROM fs_kernel.platform_event_outbox WHERE correlation_id='" + correlation + "' AND (payload_json::text LIKE '%ref:%' OR payload_json::text LIKE '%oss-test%' OR payload_json::text LIKE '%https://%')"));
        assertEquals("55000", assertThrows(SQLException.class, () -> sql(
                "UPDATE fs_kernel.platform_audit_event SET outcome='FAILURE'")).getSQLState());
        assertEquals("55000", assertThrows(SQLException.class, () -> sql(
                "DELETE FROM fs_kernel.platform_audit_event")).getSQLState());
        assertEquals(Code.CONFLICT, assertThrows(Failure.class, () ->
                configuration().publishPolicy(ADMIN, ctx, SCOPE, 1, List.of(rule(OL, 0, true)))).code());
        assertEquals(2, scalar("SELECT count(*) FROM fs_kernel.platform_event_outbox WHERE correlation_id='" + correlation + "'"));
    }

    @Test
    void requiredIntentFailureRollsBackMetadataAndLease() throws SQLException {
        for (String table : List.of("platform_audit_event", "platform_event_outbox")) {
            String column = table.equals("platform_audit_event") ? "action" : "event_type";
            sql("ALTER TABLE fs_kernel." + table + " ADD CONSTRAINT reject_test_intent CHECK (" + column +
                    " <> 'file-storage.upload.initiated') NOT VALID");
            try {
                assertThrows(JdbcTransactions.PersistenceFailure.class,
                        () -> application().initiate(ALICE, command("intent-failure")));
                assertEquals(0, count("file"));
                assertEquals(0, count("file_version"));
                assertEquals(0, count("upload_session"));
                assertEquals(0, count("initiation_request"));
                assertEquals(0, intents("platform_event_outbox", "event_type", "file-storage.upload.initiated"));
                assertEquals(0, intents("platform_audit_event", "action", "file-storage.upload.initiated"));
                assertEquals(0, scalar("SELECT count(*) FROM fs_kernel.platform_idempotency"));
            } finally {
                sql("ALTER TABLE fs_kernel." + table + " DROP CONSTRAINT reject_test_intent");
            }
        }
        assertNotNull(application().initiate(ALICE, command("intent-failure")));
        assertEquals(1, intents("platform_event_outbox", "event_type", "file-storage.upload.initiated"));
        assertEquals(1, intents("platform_audit_event", "action", "file-storage.upload.initiated"));
    }

    @Test
    void requiredConfigEventFailureRollsBackPointerAndAudit() throws SQLException {
        long audits = intents("platform_audit_event", "action", "file-storage.policy.published");
        long events = intents("platform_event_outbox", "event_type", "file-storage.policy.published");
        sql("ALTER TABLE fs_kernel.platform_event_outbox ADD CONSTRAINT reject_test_policy CHECK (event_type <> 'file-storage.policy.published') NOT VALID");
        try {
            assertThrows(JdbcTransactions.PersistenceFailure.class, () ->
                    configuration().publishPolicy(ADMIN, CorrelationContext.create(), SCOPE, 1, List.of(rule(SL, 0, true))));
            assertEquals(1, scalar("SELECT policy_revision FROM fs.active_policy"));
            assertEquals(1, count("policy_revision"));
            assertEquals(1, count("policy_rule"));
            assertEquals(audits, intents("platform_audit_event", "action", "file-storage.policy.published"));
            assertEquals(events, intents("platform_event_outbox", "event_type", "file-storage.policy.published"));
        } finally {
            sql("ALTER TABLE fs_kernel.platform_event_outbox DROP CONSTRAINT reject_test_policy");
        }
        assertEquals(2, configuration().publishPolicy(ADMIN, CorrelationContext.create(), SCOPE, 1, List.of(rule(SL, 0, true))));
    }

    @Test
    void physicalIdentityCannotBeRepointed() throws SQLException {
        application().initiate(ALICE, command("one"));
        assertEquals("23514", assertThrows(SQLException.class, () -> sql(
                "UPDATE fs.storage_location SET bucket='different' WHERE location_id=?", OL)).getSQLState());
        assertEquals("23514", assertThrows(SQLException.class, () -> sql(
                "UPDATE fs.backend SET endpoint='https://other.example.test' WHERE backend_id=?", OSS)).getSQLState());
        assertEquals(1, count("file"));
    }

    @Test
    void caughtSqlFailureCannotCommitPartialWork() throws SQLException {
        var jdbc = new JdbcTransactions(dataSource, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10));
        application().initiate(ALICE, command("one"));
        assertThrows(JdbcTransactions.PersistenceFailure.class, () -> jdbc.execute(unit -> {
            assertThrows(Failure.class, () -> unit.insert(entry(unit, "one", 120)));
            return null;
        }));
        assertEquals(1, count("file"));
    }
}
