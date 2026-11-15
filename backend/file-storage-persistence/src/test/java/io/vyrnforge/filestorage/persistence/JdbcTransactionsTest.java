package io.vyrnforge.filestorage.persistence;

import io.vyrnforge.filestorage.application.FileApplication;
import io.vyrnforge.filestorage.application.FileApplication.*;
import io.vyrnforge.filestorage.domain.StorageModel.*;
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
    static void start() {
        DB.start();
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(DB.getJdbcUrl());
        dataSource.setUser(DB.getUsername());
        dataSource.setPassword(DB.getPassword());
        StorageMigrations.migrate(dataSource);
    }

    @AfterAll
    static void stop() {
        DB.stop();
    }

    @BeforeEach
    void seed() throws SQLException {
        sql("TRUNCATE fs.backend, fs.policy_revision, fs.file, fs.file_grant, fs_kernel.platform_idempotency CASCADE");
        sql("""
                INSERT INTO fs.backend VALUES (?, 'OSS', 'https://oss.example.test', 'test', 'AUTO'),
                    (?, 'S3', 'https://s3.example.test', 'test', 'PATH')
                """, OSS, S3);
        sql("""
                INSERT INTO fs.backend_revision VALUES (?, 1, 'ref:synthetic/oss', 'ACTIVE', 1),
                    (?, 1, 'ref:synthetic/s3', 'ACTIVE', 1)
                """, OSS, S3);
        sql("INSERT INTO fs.storage_location VALUES (?, ?, 'oss-test', 'objects/'), (?, ?, 's3-test', 'objects/')",
                OL, OSS, SL, S3);
        sql("INSERT INTO fs.policy_revision VALUES (?, 'asset', 'attachment', 1)", A.value());
        sql("INSERT INTO fs.policy_rule VALUES (?, 'asset', 'attachment', 1, ?, 0, true)", A.value(), OL);
        sql("INSERT INTO fs.active_policy VALUES (?, 'asset', 'attachment', 1)", A.value());
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
        sql("UPDATE fs.policy_rule SET eligible=false");
        assertEquals(Code.NO_PLACEMENT, assertThrows(Failure.class,
                () -> application().initiate(ALICE, command("retry"))).code());
        assertEquals(0, count("file"));
        try (var c = dataSource.getConnection(); var s = c.createStatement();
             var rs = s.executeQuery("SELECT count(*) FROM fs_kernel.platform_idempotency")) {
            rs.next();
            assertEquals(0, rs.getLong(1));
        }
        sql("UPDATE fs.policy_rule SET eligible=true");
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
        sql("INSERT INTO fs.policy_revision VALUES (?, 'asset', 'attachment', 2)", A.value());
        sql("INSERT INTO fs.policy_rule VALUES (?, 'asset', 'attachment', 2, ?, 0, true)", A.value(), SL);
        sql("UPDATE fs.active_policy SET policy_revision=2 WHERE tenant_id=?", A.value());
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
        sql("UPDATE fs.policy_rule SET eligible=false");
        assertEquals(Code.NO_PLACEMENT, assertThrows(Failure.class,
                () -> application().initiate(ALICE, command("one"))).code());
        sql("UPDATE fs.policy_rule SET eligible=true");
        sql("INSERT INTO fs.policy_rule VALUES (?, 'asset', 'attachment', 1, ?, 0, true)", A.value(), SL);
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
