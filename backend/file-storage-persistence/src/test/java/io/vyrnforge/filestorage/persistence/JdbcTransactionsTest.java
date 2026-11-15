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
             var rs = statement.executeQuery("SELECT count(*) FROM fs.flyway_schema_history WHERE version IN ('1', '2', '3', '4', '5') AND success")) {
            rs.next();
            assertEquals(4, rs.getLong(1));
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
        assertEquals(3L, winner, "only the latest candidate may win");
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

    private static final class WorkerClock extends Clock {
        private Instant current = NOW;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(current, zone); }
        public Instant instant() { return current; }
        void advance(long seconds) { current = current.plusSeconds(seconds); }
    }

    private static JdbcOperations operations(Clock clock) {
        return new JdbcOperations(dataSource, clock, Duration.ofSeconds(10), Duration.ofSeconds(5), 4);
    }

    private static JdbcOperations.Request operation(String key, int maxAttempts) throws SQLException {
        var view = application().initiate(ALICE, command(key));
        var entry = new JdbcTransactions(dataSource, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10))
                .execute(u -> u.bySession(A, view.session()).orElseThrow());
        return new JdbcOperations.Request(A, entry.file().id(), entry.version().id(),
                entry.version().locator().location(), JdbcOperations.Kind.VERIFY_UPLOAD, key, id(990), maxAttempts);
    }

    private static UUID enqueue(JdbcOperations ops, JdbcOperations.Request request) throws SQLException {
        try (var c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                UUID result = ops.enqueue(c, request);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    @Test
    void operationEnqueueIsDurableIdempotentAndTenantBound() throws SQLException {
        var ops = operations(new WorkerClock());
        var request = operation("job", 3);
        try (var c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            ops.enqueue(c, request);
            c.rollback();
        }
        assertEquals(0, count("storage_operation"));
        UUID job = enqueue(ops, request);
        assertEquals(job, enqueue(operations(new WorkerClock()), request));
        assertEquals(1, count("storage_operation"));
        var changed = new JdbcOperations.Request(A, request.file(), request.version(), request.location(),
                request.kind(), request.key(), request.correlation(), 4);
        assertEquals(Code.CONFLICT, assertThrows(Failure.class, () -> enqueue(ops, changed)).code());
        var foreign = new JdbcOperations.Request(B, request.file(), request.version(), request.location(),
                request.kind(), request.key(), request.correlation(), 3);
        assertEquals("23503", assertThrows(SQLException.class, () -> enqueue(ops, foreign)).getSQLState());
        assertEquals(1, count("storage_operation"));
        assertThrows(IllegalArgumentException.class, () -> ops.claim(request.kind(), 5));
        assertTrue(ops.claim(JdbcOperations.Kind.PURGE_VERSION, 1).claims().isEmpty());
    }

    @Test
    void operationClaimsRecoverAndFenceStaleWorkers() throws SQLException {
        var clock = new WorkerClock();
        var ops = operations(clock);
        var request = operation("recover", 3);
        UUID job = enqueue(ops, request);
        var first = ops.claim(request.kind(), 1).claims().getFirst();
        assertEquals(job, first.operation());
        assertTrue(ops.stillOwned(first));
        assertTrue(ops.claim(request.kind(), 1).claims().isEmpty());
        clock.advance(10);
        assertFalse(ops.stillOwned(first));
        assertFalse(ops.finish(first, null));
        var second = operations(clock).claim(request.kind(), 1).claims().getFirst();
        assertEquals(job, second.operation());
        assertEquals(2, second.attempt());
        assertNotEquals(first.token(), second.token());
        assertFalse(ops.finish(first, JdbcOperations.ErrorCode.PERMANENT));
        assertTrue(ops.finish(second, null));
        assertFalse(ops.finish(second, null));
        assertEquals(1, scalar("SELECT count(*) FROM fs.storage_operation WHERE status='SUCCEEDED'"));
    }

    @Test
    void operationRetryExhaustionAndDeadInspection() throws SQLException {
        var clock = new WorkerClock();
        var ops = operations(clock);
        var request = operation("retry-job", 2);
        enqueue(ops, request);
        var first = ops.claim(request.kind(), 1).claims().getFirst();
        assertTrue(ops.finish(first, JdbcOperations.ErrorCode.TRANSIENT));
        assertTrue(ops.claim(request.kind(), 1).claims().isEmpty());
        clock.advance(5);
        var second = ops.claim(request.kind(), 1).claims().getFirst();
        assertEquals(2, second.attempt());
        clock.advance(10); // worker crashes during final attempt
        var exhausted = ops.claim(request.kind(), 1);
        assertEquals(1, exhausted.exhausted());
        assertTrue(exhausted.claims().isEmpty());
        assertFalse(ops.finish(second, null));
        assertEquals("ATTEMPTS_EXHAUSTED", ops.dead(A, 1).getFirst().errorCode());
        assertTrue(ops.dead(B, 1).isEmpty());
        assertTrue(ops.claim(request.kind(), 1).claims().isEmpty());
        var permanent = operation("permanent-job", 3);
        enqueue(ops, permanent);
        assertTrue(ops.finish(ops.claim(permanent.kind(), 1).claims().getFirst(), JdbcOperations.ErrorCode.PERMANENT));
        assertEquals(2, ops.dead(A, 4).size());
    }

    @Test
    void operationFinishParticipatesInCallerTransaction() throws SQLException {
        var ops = operations(new WorkerClock());
        var request = operation("finish-transaction", 2);
        enqueue(ops, request);
        var claim = ops.claim(request.kind(), 1).claims().getFirst();
        try (var c = dataSource.getConnection()) {
            assertThrows(IllegalArgumentException.class, () -> ops.finishOnConnection(c, claim, null));
            c.setAutoCommit(false);
            assertTrue(ops.finishOnConnection(c, claim, null));
            c.rollback();
        }
        assertTrue(ops.stillOwned(claim));
        assertTrue(ops.finish(claim, null));
    }

    @Test
    void concurrentWorkersClaimOnlyOneOperation() throws Exception {
        var ops = operations(new WorkerClock());
        var request = operation("concurrent-job", 3);
        enqueue(ops, request);
        var results = competing(() -> (long) ops.claim(request.kind(), 1).claims().size(),
                () -> (long) ops.claim(request.kind(), 1).claims().size());
        assertEquals(1, results.stream().filter(r -> r.equals(1L)).count());
        assertEquals(1, results.stream().filter(r -> r.equals(0L)).count());
        assertEquals(1, scalar("SELECT attempts FROM fs.storage_operation"));
    }

    @Test
    void kernelOutboxRetriesAndDeadLettersAreBounded() throws SQLException {
        // Discard seed events in this disposable test only; no production purge.
        sql("TRUNCATE fs_kernel.platform_event_outbox");
        application().initiate(ALICE, command("outbox-one"));
        application().initiate(ALICE, command("outbox-two"));
        var clock = new WorkerClock();
        var worker = new OutboxDelivery(dataSource, event -> {
            throw new IllegalStateException("synthetic private transport detail");
        }, clock, 2, Duration.ofSeconds(5), Duration.ofSeconds(10), 1);
        assertThrows(IllegalArgumentException.class, () -> worker.tick(2));
        assertEquals(1, worker.tick(1).retried());
        assertEquals(1, worker.tick(1).retried());
        assertEquals(0, worker.tick(1).claimed());
        clock.advance(5);
        assertEquals(1, worker.tick(1).dead());
        assertEquals(1, worker.tick(1).dead());
        assertEquals(2, scalar("SELECT count(*) FROM fs_kernel.platform_event_outbox WHERE status='DEAD' AND last_error_code='DeliveryFailed'"));
        assertEquals(0, worker.tick(1).claimed());
        assertEquals(1, worker.dead(A.value(), 1).size());
        assertTrue(worker.dead(B.value(), 1).isEmpty());
        assertTrue(worker.dead(null, 1).isEmpty());
    }

    @Test
    void kernelOutboxCrashAfterDeliveryRecoversWithSameEventId() throws SQLException {
        sql("TRUNCATE fs_kernel.platform_event_outbox");
        application().initiate(ALICE, command("outbox-crash"));
        var clock = new WorkerClock();
        // Synthetic durable receiver: unique event ID represents a consumer's
        // transactionally deduplicated effect. No actual consumer/broker configured.
        sql("CREATE TABLE fs.test_receiver (event_id uuid PRIMARY KEY)");
        try {
            var seen = new ArrayList<UUID>();
            var crash = new OutboxDelivery(dataSource, event -> {
                seen.add(event.eventId());
                sql("INSERT INTO fs.test_receiver VALUES (?) ON CONFLICT DO NOTHING", event.eventId());
                throw new AssertionError("synthetic process crash after send");
            }, clock, 3, Duration.ofSeconds(5), Duration.ofSeconds(10), 1);
            assertThrows(AssertionError.class, () -> crash.tick(1));
            assertEquals(1, count("test_receiver"));
            var recovery = new OutboxDelivery(dataSource, event -> {
                seen.add(event.eventId());
                sql("INSERT INTO fs.test_receiver VALUES (?) ON CONFLICT DO NOTHING", event.eventId());
            }, clock, 3, Duration.ofSeconds(5), Duration.ofSeconds(10), 1);
            assertEquals(0, recovery.tick(1).claimed());
            clock.advance(10);
            assertEquals(1, recovery.tick(1).published());
            assertEquals(2, seen.size());
            assertEquals(seen.getFirst(), seen.getLast());
            assertEquals(1, count("test_receiver"));
            assertEquals(1, scalar("SELECT count(*) FROM fs_kernel.platform_event_outbox WHERE status='PUBLISHED' AND attempts=2"));
        } finally {
            sql("DROP TABLE fs.test_receiver");
        }
    }

    @Test
    void kernelOutboxStaleClaimCannotFinalizeNewerClaim() throws SQLException {
        sql("TRUNCATE fs_kernel.platform_event_outbox");
        application().initiate(ALICE, command("outbox-fence"));
        var clock = new WorkerClock();
        var newer = new OutboxDelivery(dataSource, event -> {}, clock, 3,
                Duration.ofSeconds(5), Duration.ofSeconds(10), 1);
        var older = new OutboxDelivery(dataSource, event -> {
            clock.advance(10);
            assertEquals(1, newer.tick(1).published());
        }, clock, 3, Duration.ofSeconds(5), Duration.ofSeconds(10), 1);
        assertEquals(1, older.tick(1).lostClaims());
        assertEquals(1, scalar("SELECT count(*) FROM fs_kernel.platform_event_outbox WHERE status='PUBLISHED' AND attempts=2"));
    }

    private static JdbcRegistry registry() {
        return new JdbcRegistry(dataSource, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Target registryTarget(Provider provider, String endpoint) {
        return new Target(provider, java.net.URI.create(endpoint), "test-region", Addressing.PATH);
    }

    @Test
    void registryCreatesTwoBackendsAndPrivateLocationsWithAtomicIntents() throws SQLException {
        var registry = registry();
        var correlation = CorrelationContext.create();
        var one = registry.registerBackend(ADMIN, correlation, registryTarget(Provider.S3, "https://NEW.example.test:443/"),
                new CredentialRef("ref:synthetic/one:v1"));
        var two = registry.registerBackend(ADMIN, correlation, registryTarget(Provider.OSS, "https://other.example.test"),
                new CredentialRef("ref:synthetic/two:v1"));
        var first = registry.registerLocation(ADMIN, correlation, one, "private-test", "namespace");
        var second = registry.registerLocation(ADMIN, correlation, two, "private-test", "namespace");
        assertNotEquals(one, two);
        assertNotEquals(first, second);
        assertTrue(registry.active(one).isEmpty());
        assertEquals(BackendStatus.DRAFT, registry.history(one, 0, 1).getFirst().status());
        assertEquals(List.of(new JdbcRegistry.LocationView(first, one)), registry.locations(one, null, 1));
        assertTrue(registry.locations(one, first.value(), 1).isEmpty());
        var all = new ArrayList<JdbcRegistry.BackendView>();
        UUID cursor = null;
        do {
            var page = registry.backends(cursor, 1);
            if (page.isEmpty()) break;
            all.addAll(page);
            cursor = page.getLast().backend().value();
            assertTrue(all.size() <= 4);
        } while (true);
        assertEquals(4, all.size());
        assertTrue(all.stream().anyMatch(b -> b.backend().equals(one) && b.activeRevision() == null));
        assertEquals(1, scalar("SELECT count(*) FROM fs.backend WHERE endpoint='https://new.example.test'"));
        assertEquals(2, scalar("SELECT count(*) FROM fs.storage_location WHERE prefix='namespace/'"));
        assertEquals(2, intents("platform_event_outbox", "event_type", "file-storage.backend.registered"));
        assertEquals(2, intents("platform_audit_event", "action", "file-storage.location.registered"));
        assertEquals(4, scalar("SELECT count(*) FROM fs_kernel.platform_event_outbox WHERE correlation_id='" + correlation.correlationId() + "'"));
        assertEquals(0, scalar("SELECT count(*) FROM fs_kernel.platform_event_outbox WHERE correlation_id='" + correlation.correlationId()
                + "' AND (payload_json::text LIKE '%ref:%' OR payload_json::text LIKE '%https://%' OR payload_json::text LIKE '%private-test%')"));
        assertEquals(0, scalar("SELECT count(*) FROM fs_kernel.platform_audit_event WHERE correlation_id='" + correlation.correlationId()
                + "' AND (changes_json::text LIKE '%ref:%' OR changes_json::text LIKE '%https://%' OR changes_json::text LIKE '%private-test%')"));
        assertFalse(all.toString().contains("private-test"));
        assertThrows(Failure.class, () -> registry.backends(null, 101));
    }

    @Test
    void registryRejectsOverlapAndUnvalidatedLocationChanges() throws SQLException {
        var registry = registry();
        var correlation = CorrelationContext.create();
        var one = registry.registerBackend(ADMIN, correlation, registryTarget(Provider.S3, "https://same.example.test"),
                new CredentialRef("ref:synthetic/one:v1"));
        var two = registry.registerBackend(ADMIN, correlation, registryTarget(Provider.S3, "https://SAME.example.test:443/"),
                new CredentialRef("ref:synthetic/two:v1"));
        registry.registerLocation(ADMIN, correlation, one, "same-bucket", "area");
        assertEquals(Code.CONFLICT, assertThrows(Failure.class, () ->
                registry.registerLocation(ADMIN, correlation, two, "same-bucket", "area/nested")).code());
        assertEquals(Code.CONFLICT, assertThrows(Failure.class, () ->
                registry.registerLocation(ADMIN, correlation, two, "same-bucket", "")).code());
        assertNotNull(registry.registerLocation(ADMIN, correlation, two, "same-bucket", "area-other"));
        assertEquals(Code.INVALID, assertThrows(Failure.class, () ->
                registry.registerLocation(ADMIN, correlation, one, "same-bucket", "area/../escape")).code());
        sql("UPDATE fs.backend_revision SET validated_revision=config_revision WHERE backend_id=?", one.value());
        assertEquals(Code.CONFLICT, assertThrows(Failure.class, () ->
                registry.registerLocation(ADMIN, correlation, one, "other-bucket", "")).code());
        assertEquals(Code.CONFLICT, assertThrows(Failure.class, () ->
                registry.registerLocation(ADMIN, correlation, new BackendId(OSS), "other-bucket", "")).code());
        assertEquals(Code.NOT_FOUND, assertThrows(Failure.class, () ->
                registry.registerLocation(ADMIN, correlation, new BackendId(id(998)), "other-bucket", "")).code());
        assertEquals(4, count("storage_location"));
    }

    private static final class SyntheticCredential implements AutoCloseable {
        boolean closed;
        final String reference;
        SyntheticCredential(String reference) { this.reference = reference; }
        public void close() { closed = true; }
    }

    @Test
    void registryRotationReadsExactPinnedRevisionAndFailsClosedOnRevocation() throws SQLException {
        var registry = registry();
        var backend = new BackendId(OSS);
        var location = new LocationId(OL);
        var correlation = CorrelationContext.create();
        var initial = application().initiate(ALICE, command("before-rotation"));
        var refs = new HashSet<>(Set.of("ref:synthetic/oss", "ref:synthetic/oss:v2"));
        var calls = new ArrayList<String>();
        io.vyrnforge.filestorage.application.CredentialResolver<SyntheticCredential> resolver = reference -> {
            calls.add(reference.value());
            if (!refs.contains(reference.value())) throw new IllegalStateException("synthetic revoked private detail");
            return new SyntheticCredential(reference.value());
        };
        assertEquals(2, registry.reviseCredential(ADMIN, correlation, backend, 1,
                new CredentialRef("ref:synthetic/oss:v2")));
        assertEquals(1, registry().active(backend).orElseThrow().revision());
        assertEquals(2, registry().history(backend, 0, 1).getFirst().revision());
        assertEquals(1, registry.history(backend, 2, 1).getFirst().revision());
        assertEquals(Code.NOT_FOUND, assertThrows(Failure.class,
                () -> registry.resolveExisting(backend, 2, location, resolver)).code());
        assertEquals(Code.NOT_FOUND, assertThrows(Failure.class,
                () -> registry.resolveExisting(backend, 1, new LocationId(SL), resolver)).code());
        assertTrue(calls.isEmpty());
        sql("UPDATE fs.backend_revision SET validated_revision=config_revision WHERE backend_id=? AND config_revision=2", OSS);
        configuration().activateBackend(ADMIN, correlation, backend, 1, 2);
        assertEquals(2, registry().active(backend).orElseThrow().revision());
        assertEquals(BackendStatus.DRAINING, registry().history(backend, 2, 1).getFirst().status());
        SyntheticCredential old;
        try (var resolved = registry.resolveExisting(backend, 1, location, resolver)) {
            old = resolved.credential();
            assertEquals("ref:synthetic/oss", old.reference);
            assertEquals(1, resolved.revision());
            assertEquals("Resolved[redacted]", resolved.toString());
        }
        assertTrue(old.closed);
        try (var resolved = registry.resolveExisting(backend, 2, location, resolver)) {
            assertEquals("ref:synthetic/oss:v2", resolved.credential().reference);
        }
        refs.remove("ref:synthetic/oss");
        var denied = assertThrows(Failure.class, () -> registry.resolveExisting(backend, 1, location, resolver));
        assertEquals(Code.DENIED, denied.code());
        assertNull(denied.getCause());
        assertEquals(List.of("ref:synthetic/oss", "ref:synthetic/oss:v2", "ref:synthetic/oss"), calls);
        assertEquals(3, registry.reviseCredential(ADMIN, correlation, backend, 2,
                new CredentialRef("ref:synthetic/oss"))); // rollback is a new draft, not a rewind
        assertEquals(2, registry.active(backend).orElseThrow().revision());
        assertEquals(BackendStatus.DRAFT, registry.history(backend, 0, 1).getFirst().status());
        var persisted = new JdbcTransactions(dataSource, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10))
                .execute(u -> u.bySession(A, initial.session()).orElseThrow());
        assertEquals(1, persisted.session().placement().configRevision());
        assertEquals(OL, persisted.session().placement().location().value());
        // Simulate authority restoring the exact prior version before rollback validation.
        // A revoked version may never be used merely because it was valid historically.
        refs.add("ref:synthetic/oss");
        sql("UPDATE fs.backend_revision SET validated_revision=config_revision WHERE backend_id=? AND config_revision=3", OSS);
        configuration().activateBackend(ADMIN, correlation, backend, 2, 3);
        assertEquals(3, registry().active(backend).orElseThrow().revision());
        try (var resolved = registry.resolveExisting(backend, 3, location, resolver)) {
            assertEquals("ref:synthetic/oss", resolved.credential().reference);
            assertEquals(3, resolved.revision());
        }
        assertEquals(BackendStatus.DRAINING, registry.history(backend, 3, 1).getFirst().status());
        assertEquals(Code.DENIED, assertThrows(Failure.class,
                () -> registry.resolveExisting(backend, 3, location,
                        (io.vyrnforge.filestorage.application.CredentialResolver<SyntheticCredential>) ref -> null)).code());
    }

    @Test
    void registryConcurrentOverlappingLocationsHaveOneWinner() throws Exception {
        var registry = registry();
        var ctx = CorrelationContext.create();
        var one = registry.registerBackend(ADMIN, ctx, registryTarget(Provider.S3, "https://concurrent.example.test"),
                new CredentialRef("ref:synthetic/concurrent:v1"));
        var two = registry.registerBackend(ADMIN, ctx, registryTarget(Provider.S3, "https://CONCURRENT.example.test:443/"),
                new CredentialRef("ref:synthetic/concurrent:v2"));
        var results = competing(() -> {
            registry.registerLocation(ADMIN, ctx, one, "concurrent-bucket", "area");
            return 1L;
        }, () -> {
            registry.registerLocation(ADMIN, ctx, two, "concurrent-bucket", "area/nested");
            return 1L;
        });
        assertEquals(1, results.stream().filter(v -> v.equals(1L)).count());
        assertEquals(1, results.stream().filter(v -> v == Code.CONFLICT).count());
        assertEquals(1, scalar("SELECT count(*) FROM fs.storage_location WHERE bucket='concurrent-bucket'"));
        assertEquals(1, intents("platform_audit_event", "action", "file-storage.location.registered"));
        assertEquals(1, intents("platform_event_outbox", "event_type", "file-storage.location.registered"));
    }

    @Test
    void registryConcurrentRevisionCasAndAtomicIntentFailure() throws Exception {
        var registry = registry();
        var backend = new BackendId(OSS);
        var results = competing(() -> registry.reviseCredential(ADMIN, CorrelationContext.create(), backend, 1,
                new CredentialRef("ref:synthetic/first:v2")),
                () -> registry.reviseCredential(ADMIN, CorrelationContext.create(), backend, 1,
                new CredentialRef("ref:synthetic/second:v2")));
        assertEquals(1, results.stream().filter(v -> v.equals(2L)).count());
        assertEquals(1, results.stream().filter(v -> v == Code.CONFLICT).count());
        assertEquals(2, registry.history(backend, 0, 10).size());
        assertEquals(1, intents("platform_event_outbox", "event_type", "file-storage.backend.credential-revised"));
        sql("ALTER TABLE fs_kernel.platform_event_outbox ADD CONSTRAINT reject_registry_event CHECK (event_type <> 'file-storage.backend.registered') NOT VALID");
        try {
            assertThrows(RuntimeException.class, () -> registry.registerBackend(ADMIN, CorrelationContext.create(),
                    registryTarget(Provider.S3, "https://rollback.example.test"), new CredentialRef("ref:synthetic/rollback")));
            assertEquals(2, count("backend"));
            assertEquals(3, count("backend_revision"));
            assertEquals(0, intents("platform_audit_event", "action", "file-storage.backend.registered"));
        } finally {
            sql("ALTER TABLE fs_kernel.platform_event_outbox DROP CONSTRAINT reject_registry_event");
        }
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

    @Test
    void explicitDrainStopsNewPlacementButPreservesExistingPin() throws SQLException {
        var first = application().initiate(ALICE, command("before-drain"));
        var backend = new BackendId(OSS);
        var ctx = CorrelationContext.create();
        var config = configuration();
        assertEquals(Code.CONFLICT, assertThrows(Failure.class,
                () -> config.drainBackend(ADMIN, ctx, backend, 2)).code());
        assertEquals(1, config.drainBackend(ADMIN, ctx, backend, 1));
        assertTrue(registry().active(backend).isEmpty());
        assertEquals(BackendStatus.DRAINING, registry().history(backend, 0, 1).getFirst().status());
        assertEquals(Code.NO_PLACEMENT, assertThrows(Failure.class,
                () -> application().initiate(ALICE, command("after-drain"))).code());
        assertEquals(first, application().initiate(ALICE, command("before-drain")));
        try (var resolved = registry().resolveExisting(backend, 1, new LocationId(OL),
                ref -> new SyntheticCredential(ref.value()))) {
            assertEquals(1, resolved.revision());
            assertEquals("ref:synthetic/oss", resolved.credential().reference);
        }
        assertEquals(2, config.publishPolicy(ADMIN, ctx, SCOPE, 1, List.of(rule(SL, 0, true))));
        var next = application().initiate(ALICE, command("replacement"));
        var jdbc = new JdbcTransactions(dataSource, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10));
        assertEquals(SL, jdbc.execute(u -> u.bySession(A, next.session()).orElseThrow()).session().placement().location().value());
        assertEquals(OL, jdbc.execute(u -> u.bySession(A, first.session()).orElseThrow()).session().placement().location().value());
        assertEquals(1, intents("platform_audit_event", "action", "file-storage.backend.drained"));
        assertEquals(1, intents("platform_event_outbox", "event_type", "file-storage.backend.drained"));
        assertEquals(Code.CONFLICT, assertThrows(Failure.class,
                () -> config.drainBackend(ADMIN, ctx, backend, 1)).code());
    }

    @Test
    void concurrentDrainAndActivationHaveOneWinner() throws Exception {
        draft(2, true);
        var backend = new BackendId(OSS);
        var results = competing(
                () -> configuration().drainBackend(ADMIN, CorrelationContext.create(), backend, 1),
                () -> configuration().activateBackend(ADMIN, CorrelationContext.create(), backend, 1, 2));
        assertEquals(1, results.stream().filter(v -> v == Code.CONFLICT).count());
        assertEquals(1, results.stream().filter(v -> v.equals(1L) || v.equals(2L)).count());
        assertEquals(BackendStatus.DRAINING, registry().history(backend, 2, 1).getFirst().status());
        assertEquals(results.contains(2L), registry().active(backend).isPresent());
    }

    @Test
    void requiredDrainIntentFailureRollsBackStatus() throws SQLException {
        var backend = new BackendId(OSS);
        sql("ALTER TABLE fs_kernel.platform_event_outbox ADD CONSTRAINT reject_test_drain CHECK (event_type <> 'file-storage.backend.drained') NOT VALID");
        try {
            assertThrows(RuntimeException.class, () ->
                    configuration().drainBackend(ADMIN, CorrelationContext.create(), backend, 1));
            assertEquals(1, registry().active(backend).orElseThrow().revision());
            assertEquals(0, intents("platform_audit_event", "action", "file-storage.backend.drained"));
            assertEquals(0, intents("platform_event_outbox", "event_type", "file-storage.backend.drained"));
        } finally {
            sql("ALTER TABLE fs_kernel.platform_event_outbox DROP CONSTRAINT reject_test_drain");
        }
        assertEquals(1, configuration().drainBackend(ADMIN, CorrelationContext.create(), backend, 1));
    }


    private static io.vyrnforge.filestorage.application.BackendValidator.Report validReport(
            io.vyrnforge.filestorage.application.BackendValidator.Candidate candidate) {
        var checks = new HashMap<LocationId, Set<io.vyrnforge.filestorage.application.BackendValidator.Check>>();
        for (var location : candidate.locations()) {
            checks.put(location.id(), EnumSet.allOf(io.vyrnforge.filestorage.application.BackendValidator.Check.class));
        }
        return new io.vyrnforge.filestorage.application.BackendValidator.Report(
                candidate.backend(), candidate.revision(), checks);
    }

    @Test
    void trustedValidationRequiresApprovedTargetAndCompleteExactReport() throws SQLException {
        var registry = registry();
        var ctx = CorrelationContext.create();
        var target = registryTarget(Provider.S3, "https://validation.example.test");
        var backend = registry.registerBackend(ADMIN, ctx, target, new CredentialRef("ref:synthetic/validation:v1"));
        registry.registerLocation(ADMIN, ctx, backend, "validation-bucket", "one");
        registry.registerLocation(ADMIN, ctx, backend, "validation-bucket", "two");
        assertEquals(Code.DENIED, assertThrows(Failure.class, () ->
                registry.validateDraft(ADMIN, ctx, backend, 1, Set.of(), c -> {
                    throw new AssertionError("unapproved endpoint was contacted");
                })).code());
        assertEquals(Code.DENIED, assertThrows(Failure.class, () ->
                registry.validateDraft(ADMIN, ctx, backend, 1, Set.of(target), c ->
                        new io.vyrnforge.filestorage.application.BackendValidator.Report(backend, 1, Map.of()))).code());
        assertEquals(Code.DENIED, assertThrows(Failure.class, () ->
                registry.validateDraft(ADMIN, ctx, backend, 1, Set.of(target), c ->
                        new io.vyrnforge.filestorage.application.BackendValidator.Report(backend, 2, validReport(c).checks()))).code());
        assertFalse(registry.history(backend, 0, 1).getFirst().validated());
        assertEquals(1, registry.validateDraft(ADMIN, ctx, backend, 1, Set.of(target), c -> {
            assertEquals("Candidate[redacted]", c.toString());
            assertEquals(2, c.locations().size());
            return validReport(c);
        }));
        assertTrue(registry.history(backend, 0, 1).getFirst().validated());
        assertEquals(1, configuration().activateBackend(ADMIN, ctx, backend, 0, 1));
        assertEquals(1, intents("platform_event_outbox", "event_type", "file-storage.backend.validated"));
        assertEquals(1, intents("platform_audit_event", "action", "file-storage.backend.validated"));
        var unsafe = registryTarget(Provider.S3, "https://169.254.169.254");
        var bad = registry.registerBackend(ADMIN, ctx, unsafe, new CredentialRef("ref:synthetic/unsafe:v1"));
        registry.registerLocation(ADMIN, ctx, bad, "validation-bucket", "one");
        assertEquals(Code.DENIED, assertThrows(Failure.class, () ->
                registry.validateDraft(ADMIN, ctx, bad, 1, Set.of(unsafe), c -> {
                    throw new AssertionError("literal endpoint was contacted");
                })).code());
    }

    @Test
    void draftValidationRejectsLocationSetChangedDuringExternalCheck() throws SQLException {
        var registry = registry();
        var ctx = CorrelationContext.create();
        var target = registryTarget(Provider.S3, "https://stale-validation.example.test");
        var backend = registry.registerBackend(ADMIN, ctx, target, new CredentialRef("ref:synthetic/stale:v1"));
        registry.registerLocation(ADMIN, ctx, backend, "validation-bucket", "one");
        assertEquals(Code.CONFLICT, assertThrows(Failure.class, () ->
                registry.validateDraft(ADMIN, ctx, backend, 1, Set.of(target), c -> {
                    registry().registerLocation(ADMIN, ctx, backend, "validation-bucket", "two");
                    return validReport(c);
                })).code());
        assertFalse(registry.history(backend, 0, 1).getFirst().validated());
        assertEquals(Code.INVALID, assertThrows(Failure.class, () ->
                configuration().activateBackend(ADMIN, ctx, backend, 0, 1)).code());
        assertEquals(0, intents("platform_event_outbox", "event_type", "file-storage.backend.validated"));
        assertEquals(1, registry.validateDraft(ADMIN, ctx, backend, 1, Set.of(target), JdbcTransactionsTest::validReport));
    }

    @Test
    void guardedRetirementPreservesHistoryAndRejectsExistingReferences() throws SQLException {
        var config = configuration();
        var ctx = CorrelationContext.create();
        var oss = new BackendId(OSS);
        var s3 = new BackendId(S3);
        assertEquals(Code.CONFLICT, assertThrows(Failure.class, () -> config.retireBackend(ADMIN, ctx, s3, 1)).code());
        config.drainBackend(ADMIN, ctx, s3, 1);
        assertEquals(1, config.retireBackend(ADMIN, ctx, s3, 1));
        assertEquals(BackendStatus.RETIRED, registry().history(s3, 0, 1).getFirst().status());
        assertEquals(Code.NOT_FOUND, assertThrows(Failure.class, () ->
                registry().resolveExisting(s3, 1, new LocationId(SL), ref -> new SyntheticCredential(ref.value()))).code());
        assertEquals(1, registry().locations(s3, null, 10).size());
        assertEquals(Code.CONFLICT, assertThrows(Failure.class, () -> config.retireBackend(ADMIN, ctx, s3, 1)).code());
        // Active policy alone blocks retirement, even with no uploaded file.
        config.drainBackend(ADMIN, ctx, oss, 1);
        assertEquals(Code.CONFLICT, assertThrows(Failure.class, () -> config.retireBackend(ADMIN, ctx, oss, 1)).code());
        // The separate test below covers actual references and jobs after routing moves.
        assertEquals(Code.CONFLICT, assertThrows(Failure.class, () -> config.retireBackend(ADMIN, ctx, oss, 1)).code());
        assertEquals("23514", assertThrows(SQLException.class, () ->
                sql("UPDATE fs.backend_revision SET status='RETIRED' WHERE backend_id=?", OSS)).getSQLState());
        assertEquals(0, count("file_version"));
        assertEquals(0, count("upload_session"));
        assertEquals(1, intents("platform_event_outbox", "event_type", "file-storage.backend.retired"));
    }

    @Test
    void retirementBlocksExistingVersionSessionAndRecoverableJobAfterPolicySwitch() throws SQLException {
        var config = configuration();
        var ctx = CorrelationContext.create();
        var oss = new BackendId(OSS);
        var ops = operations(new WorkerClock());
        var request = operation("retirement-job", 3);
        var job = enqueue(ops, request);
        assertEquals(job, ops.claim(request.kind(), 1).claims().getFirst().operation());
        config.publishPolicy(ADMIN, ctx, SCOPE, 1, List.of(rule(SL, 0, true)));
        config.drainBackend(ADMIN, ctx, oss, 1);
        assertEquals(Code.CONFLICT, assertThrows(Failure.class, () -> config.retireBackend(ADMIN, ctx, oss, 1)).code());
        assertEquals(1, count("file_version"));
        assertEquals(1, count("upload_session"));
        assertEquals(1, count("storage_operation"));
        assertEquals(BackendStatus.DRAINING, registry().history(oss, 0, 1).getFirst().status());
        assertEquals(0, intents("platform_event_outbox", "event_type", "file-storage.backend.retired"));
    }

    @Test
    void retirementFencesRepeatableReadInitiationWithOldPlacement() throws SQLException {
        var jdbc = new JdbcTransactions(dataSource, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10));
        var config = configuration();
        var ctx = CorrelationContext.create();
        var oss = new BackendId(OSS);
        assertEquals(Code.CONFLICT, assertThrows(Failure.class, () -> jdbc.execute(u -> {
            var pending = entry(u, "stale-retirement", 999);
            config.publishPolicy(ADMIN, ctx, SCOPE, 1, List.of(rule(SL, 0, true)));
            config.drainBackend(ADMIN, ctx, oss, 1);
            config.retireBackend(ADMIN, ctx, oss, 1);
            u.insert(pending);
            return null;
        })).code());
        assertEquals(0, count("file"));
        assertEquals(0, count("file_version"));
        assertEquals(0, count("upload_session"));
        assertEquals(BackendStatus.RETIRED, registry().history(oss, 0, 1).getFirst().status());
        assertNotNull(application().initiate(ALICE, command("fresh-after-retirement")));
    }

    @Test
    void retirementIntentFailureRollsBackFenceAndRevision() throws SQLException {
        var s3 = new BackendId(S3);
        var ctx = CorrelationContext.create();
        var config = configuration();
        config.drainBackend(ADMIN, ctx, s3, 1);
        sql("ALTER TABLE fs_kernel.platform_event_outbox ADD CONSTRAINT reject_test_retirement CHECK (event_type <> 'file-storage.backend.retired') NOT VALID");
        try {
            assertThrows(RuntimeException.class, () -> config.retireBackend(ADMIN, ctx, s3, 1));
            assertEquals(BackendStatus.DRAINING, registry().history(s3, 0, 1).getFirst().status());
            assertEquals(0, intents("platform_audit_event", "action", "file-storage.backend.retired"));
        } finally {
            sql("ALTER TABLE fs_kernel.platform_event_outbox DROP CONSTRAINT reject_test_retirement");
        }
        assertEquals(1, config.retireBackend(ADMIN, ctx, s3, 1));
    }


    private static JdbcPolicyControl policies(io.vyrnforge.filestorage.application.PolicyAuthority authority) {
        return new JdbcPolicyControl(dataSource, Clock.fixed(NOW, ZoneOffset.UTC), authority);
    }

    @Test
    void policyControlSwitchesAndRollsBackWithoutChangingExistingPlacements() throws SQLException {
        var policy = policies((actor, scope, locations) -> actor.equals(ADMIN) && scope.equals(SCOPE)
                && Set.of(new LocationId(OL), new LocationId(SL)).containsAll(locations));
        var ctx = CorrelationContext.create();
        var old = application().initiate(ALICE, command("policy-old"));
        assertEquals(1L, policy.active(ADMIN, SCOPE).orElseThrow().revision());
        assertEquals(2L, policy.publish(ADMIN, ctx, SCOPE, 1, List.of(rule(SL, 0, true))));
        var next = application().initiate(ALICE, command("policy-new"));
        assertEquals(2L, policy.active(ADMIN, SCOPE).orElseThrow().revision());
        assertEquals(List.of(rule(OL, 0, true)), policy.revision(ADMIN, SCOPE, 1).orElseThrow().rules());
        assertEquals(3L, policy.rollback(ADMIN, ctx, SCOPE, 2, 1));
        var restored = application().initiate(ALICE, command("policy-restored"));
        var jdbc = new JdbcTransactions(dataSource, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10));
        var first = jdbc.execute(u -> u.bySession(A, old.session()).orElseThrow());
        var second = jdbc.execute(u -> u.bySession(A, next.session()).orElseThrow());
        var third = jdbc.execute(u -> u.bySession(A, restored.session()).orElseThrow());
        assertEquals(new LocationId(OL), first.session().placement().location());
        assertEquals(new LocationId(SL), second.session().placement().location());
        assertEquals(new LocationId(OL), third.session().placement().location());
        assertEquals(1L, first.session().placement().policyRevision());
        assertEquals(2L, second.session().placement().policyRevision());
        assertEquals(3L, third.session().placement().policyRevision());
        assertEquals(old, application().initiate(ALICE, command("policy-old")));
        assertEquals(List.of(3L, 2L), policy.history(ADMIN, SCOPE, 0, 2));
        assertEquals(List.of(1L), policy.history(ADMIN, SCOPE, 2, 2));
        assertEquals(List.of(rule(OL, 0, true)), policy.active(ADMIN, SCOPE).orElseThrow().rules());
        assertEquals(1, scalar("SELECT count(*) FROM fs_kernel.platform_event_outbox WHERE event_type='file-storage.policy.published' AND payload_json->>'rollbackSourceRevision'='1'"));
        assertEquals(1, scalar("SELECT count(*) FROM fs_kernel.platform_audit_event WHERE action='file-storage.policy.published' AND changes_json->>'rollbackSourceRevision'='1'"));
    }

    @Test
    void policyControlDeniesOtherScopesAndEveryUnapprovedLocation() throws SQLException {
        var policy = policies((actor, scope, locations) -> actor.equals(ADMIN) && scope.equals(SCOPE)
                && Set.of(new LocationId(OL)).containsAll(locations));
        var ctx = CorrelationContext.create();
        for (Scope wrong : List.of(new Scope(B, "asset", "attachment"),
                new Scope(A, "itsm", "attachment"), new Scope(A, "asset", "other"))) {
            assertEquals(Code.DENIED, assertThrows(Failure.class,
                    () -> policy.active(ADMIN, wrong)).code());
            assertEquals(Code.DENIED, assertThrows(Failure.class,
                    () -> policy.publish(ADMIN, ctx, wrong, 0, List.of(rule(OL, 0, true)))).code());
        }
        var stranger = new ActorReference(ActorType.USER, "other-admin", null, null);
        assertEquals(Code.DENIED, assertThrows(Failure.class, () -> policy.history(stranger, SCOPE, 0, 10)).code());
        // A non-winning or disabled rule may not smuggle an unapproved location.
        assertEquals(Code.DENIED, assertThrows(Failure.class, () -> policy.publish(ADMIN, ctx, SCOPE, 1,
                List.of(rule(OL, 0, true), rule(SL, 1, false)))).code());
        var broken = policies((actor, scope, locations) -> { throw new IllegalStateException("authority unavailable"); });
        assertEquals(Code.DENIED, assertThrows(Failure.class, () -> broken.active(ADMIN, SCOPE)).code());
        assertEquals(1, count("policy_revision"));
        assertEquals(1, scalar("SELECT count(*) FROM fs_kernel.platform_event_outbox WHERE event_type='file-storage.policy.published'"));
    }

    @Test
    void policyControlRollbackReauthorizesAndRejectsDrainedOrStaleRouting() throws SQLException {
        var allowed = new HashSet<>(Set.of(new LocationId(OL), new LocationId(SL)));
        var policy = policies((actor, scope, locations) -> actor.equals(ADMIN) && scope.equals(SCOPE)
                && allowed.containsAll(locations));
        var ctx = CorrelationContext.create();
        assertEquals(2L, policy.publish(ADMIN, ctx, SCOPE, 1, List.of(rule(SL, 0, true))));
        allowed.remove(new LocationId(OL));
        assertEquals(Code.DENIED, assertThrows(Failure.class, () -> policy.rollback(ADMIN, ctx, SCOPE, 2, 1)).code());
        assertEquals(Code.DENIED, assertThrows(Failure.class, () -> policy.revision(ADMIN, SCOPE, 1)).code());
        allowed.add(new LocationId(OL));
        assertEquals(Code.CONFLICT, assertThrows(Failure.class, () -> policy.rollback(ADMIN, ctx, SCOPE, 3, 1)).code());
        configuration().drainBackend(ADMIN, ctx, new BackendId(OSS), 1);
        assertEquals(Code.NO_PLACEMENT, assertThrows(Failure.class, () -> policy.rollback(ADMIN, ctx, SCOPE, 2, 1)).code());
        assertEquals(2L, policy.active(ADMIN, SCOPE).orElseThrow().revision());
        assertEquals(2, count("policy_revision"));
        assertEquals(0, scalar("SELECT count(*) FROM fs_kernel.platform_event_outbox WHERE event_type='file-storage.policy.published' AND payload_json ? 'rollbackSourceRevision'"));
    }

    @Test
    void policyControlRejectsAmbiguousIneligibleAndOversizedRules() throws SQLException {
        var policy = policies((actor, scope, locations) -> true);
        var ctx = CorrelationContext.create();
        assertEquals(Code.NO_PLACEMENT, assertThrows(Failure.class, () -> policy.publish(ADMIN, ctx, SCOPE, 1,
                List.of(rule(OL, 0, true), rule(SL, 0, true)))).code());
        assertEquals(Code.NO_PLACEMENT, assertThrows(Failure.class, () -> policy.publish(ADMIN, ctx, SCOPE, 1,
                List.of(rule(OL, 0, false), rule(SL, 1, true)))).code());
        assertEquals(Code.INVALID, assertThrows(Failure.class, () -> policy.publish(ADMIN, ctx, SCOPE, 1,
                List.of(rule(OL, 0, true), rule(OL, 1, true)))).code());
        assertEquals(Code.INVALID, assertThrows(Failure.class, () -> policy.publish(ADMIN, ctx, SCOPE, 1,
                Collections.nCopies(101, rule(OL, 0, true)))).code());
        assertEquals(Code.INVALID, assertThrows(Failure.class, () -> policy.history(ADMIN, SCOPE, 0, 101)).code());
        assertEquals(1L, policy.active(ADMIN, SCOPE).orElseThrow().revision());
        assertEquals(1, count("policy_revision"));
    }

    @Test
    void supersededValidatedDraftCannotActivateOrBeRevalidated() throws SQLException {
        var registry = registry();
        var ctx = CorrelationContext.create();
        var target = registryTarget(Provider.S3, "https://superseded.example.test");
        var backend = registry.registerBackend(ADMIN, ctx, target, new CredentialRef("ref:synthetic/superseded:v1"));
        registry.registerLocation(ADMIN, ctx, backend, "validation-bucket", "one");
        assertEquals(1, registry.validateDraft(ADMIN, ctx, backend, 1, Set.of(target), JdbcTransactionsTest::validReport));
        assertEquals(2, registry.reviseCredential(ADMIN, ctx, backend, 1, new CredentialRef("ref:synthetic/superseded:v2")));
        long activationEvents = intents("platform_event_outbox", "event_type", "file-storage.backend.activated");
        long activationAudits = intents("platform_audit_event", "action", "file-storage.backend.activated");
        assertEquals(Code.CONFLICT, assertThrows(Failure.class, () ->
                configuration().activateBackend(ADMIN, ctx, backend, 0, 1)).code());
        assertEquals(Code.CONFLICT, assertThrows(Failure.class, () ->
                registry.validateDraft(ADMIN, ctx, backend, 1, Set.of(target), c -> {
                    throw new AssertionError("superseded candidate must not reach validator");
                })).code());
        assertTrue(registry.active(backend).isEmpty());
        assertEquals(activationEvents, intents("platform_event_outbox", "event_type", "file-storage.backend.activated"));
        assertEquals(activationAudits, intents("platform_audit_event", "action", "file-storage.backend.activated"));
        assertEquals(2, registry.validateDraft(ADMIN, ctx, backend, 2, Set.of(target), JdbcTransactionsTest::validReport));
        assertEquals(2, configuration().activateBackend(ADMIN, ctx, backend, 0, 2));
        assertEquals(2, registry.active(backend).orElseThrow().revision());
        assertEquals(activationEvents + 1, intents("platform_event_outbox", "event_type", "file-storage.backend.activated"));
        assertEquals(activationAudits + 1, intents("platform_audit_event", "action", "file-storage.backend.activated"));
    }

    @Test
    void draftValidationRejectsCredentialRevisionAppendedDuringExternalCheck() throws SQLException {
        var registry = registry();
        var ctx = CorrelationContext.create();
        var target = registryTarget(Provider.S3, "https://revision-race.example.test");
        var backend = registry.registerBackend(ADMIN, ctx, target, new CredentialRef("ref:synthetic/race:v1"));
        registry.registerLocation(ADMIN, ctx, backend, "validation-bucket", "one");
        assertEquals(Code.CONFLICT, assertThrows(Failure.class, () ->
                registry.validateDraft(ADMIN, ctx, backend, 1, Set.of(target), candidate -> {
                    assertEquals(2, registry().reviseCredential(ADMIN, ctx, backend, 1,
                            new CredentialRef("ref:synthetic/race:v2")));
                    return validReport(candidate);
                })).code());
        assertTrue(registry.history(backend, 0, 100).stream().noneMatch(JdbcRegistry.RevisionView::validated));
        assertEquals(0, intents("platform_event_outbox", "event_type", "file-storage.backend.validated"));
        assertEquals(0, intents("platform_audit_event", "action", "file-storage.backend.validated"));
        assertEquals(Code.CONFLICT, assertThrows(Failure.class, () ->
                configuration().activateBackend(ADMIN, ctx, backend, 0, 1)).code());
        assertEquals(2, registry.validateDraft(ADMIN, ctx, backend, 2, Set.of(target), JdbcTransactionsTest::validReport));
        assertEquals(2, configuration().activateBackend(ADMIN, ctx, backend, 0, 2));
    }
}
