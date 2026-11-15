package io.vyrnforge.filestorage.persistence;

import io.vyrnforge.filestorage.application.FileApplication.Code;
import io.vyrnforge.filestorage.application.FileApplication.Failure;
import io.vyrnforge.filestorage.domain.StorageModel.FileId;
import io.vyrnforge.filestorage.domain.StorageModel.LocationId;
import io.vyrnforge.filestorage.domain.StorageModel.TenantId;
import io.vyrnforge.filestorage.domain.StorageModel.VersionId;
import io.vyrnforge.platform.kernel.ids.UuidV7;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Internal durable operation ledger. No provider I/O or authorization here.
 * Only reviewed handlers may claim a supported kind and resolve the pinned resource.
 * External effects must be idempotent/reconciled; a lease fences DB state, not a provider.
 */
public final class JdbcOperations {
    public enum Kind { VERIFY_UPLOAD, SCAN_UPLOAD, CLEANUP_UPLOAD, PURGE_VERSION, MIGRATE_VERSION }
    public enum ErrorCode { TRANSIENT, PERMANENT }
    public record Request(TenantId tenant, FileId file, VersionId version, LocationId location,
                          Kind kind, String key, UUID correlation, int maxAttempts) {
        public Request {
            Objects.requireNonNull(tenant);
            Objects.requireNonNull(file);
            Objects.requireNonNull(version);
            Objects.requireNonNull(location);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(correlation);
            if (key == null || key.isBlank() || key.length() > 128 || maxAttempts < 1
                    || !UuidV7.isCanonical(correlation.toString())) {
                throw new Failure(Code.INVALID);
            }
        }
    }

    public record Claim(UUID operation, TenantId tenant, FileId file, VersionId version,
                        LocationId location, Kind kind, UUID correlation, UUID token,
                        Instant expiresAt, int attempt) {}
    public record Batch(List<Claim> claims, int exhausted) {
        public Batch {
            claims = List.copyOf(claims);
        }
    }
    public record Dead(UUID operation, Kind kind, FileId file, VersionId version,
                       int attempts, String errorCode, Instant finishedAt) {}

    private final DataSource dataSource;
    private final Clock clock;
    private final Duration lease;
    private final Duration retryDelay;
    private final int maxBatch;

    public JdbcOperations(DataSource dataSource, Clock clock, Duration lease, Duration retryDelay, int maxBatch) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.clock = Objects.requireNonNull(clock);
        this.lease = Objects.requireNonNull(lease);
        this.retryDelay = Objects.requireNonNull(retryDelay);
        if (lease.compareTo(Duration.ofNanos(1000)) < 0 || retryDelay.isNegative() || maxBatch < 1) {
            throw new IllegalArgumentException("invalid worker bounds");
        }
        this.maxBatch = maxBatch;
    }

    /**
     * Enqueue on a caller-owned transaction, so future metadata transitions and work
     * can commit together. Caller must roll back on failure. No network side effects.
     */
    public UUID enqueue(Connection connection, Request request) throws SQLException {
        Objects.requireNonNull(connection);
        Objects.requireNonNull(request);
        if (connection.getAutoCommit()) {
            throw new IllegalArgumentException("transaction required");
        }
        UUID id = UuidV7.generate();
        var now = time(clock.instant());
        try (var ps = statement(connection, """
                INSERT INTO fs.storage_operation (operation_id, tenant_id, file_id, version_id,
                    location_id, kind, operation_key, correlation_id, max_attempts, available_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, kind, operation_key) DO NOTHING
                RETURNING operation_id
                """, id, request.tenant().value(), request.file().value(), request.version().value(),
                request.location().value(), request.kind().name(), request.key(), request.correlation(),
                request.maxAttempts(), now, now); var rs = ps.executeQuery()) {
            if (rs.next()) return rs.getObject(1, UUID.class);
        }
        try (var ps = statement(connection, """
                SELECT operation_id, file_id, version_id, location_id, max_attempts
                FROM fs.storage_operation WHERE tenant_id=? AND kind=? AND operation_key=?
                """, request.tenant().value(), request.kind().name(), request.key());
             var rs = ps.executeQuery()) {
            if (!rs.next() || !request.file().value().equals(rs.getString(2))
                    || !request.version().value().equals(rs.getObject(3, UUID.class))
                    || !request.location().value().equals(rs.getObject(4, UUID.class))
                    || request.maxAttempts() != rs.getInt(5)) {
                throw new Failure(Code.CONFLICT);
            }
            return rs.getObject(1, UUID.class);
        }
    }

    /** Bounded claim, including expired exhausted rows, on an independent transaction. */
    public Batch claim(Kind kind, int limit) {
        Objects.requireNonNull(kind);
        bound(limit);
        Instant now = clock.instant();
        var until = time(now.plus(lease));
        var current = time(now);
        UUID token = UuidV7.generate();
        // One SQL statement is atomic; locks are released before the worker performs I/O.
        String sql = """
                WITH candidates AS (
                    SELECT operation_id FROM fs.storage_operation
                    WHERE kind=? AND status='PENDING' AND available_at<=?
                        AND (locked_until IS NULL OR locked_until<=?)
                    ORDER BY available_at, operation_id
                    FOR UPDATE SKIP LOCKED LIMIT ?
                )
                UPDATE fs.storage_operation o SET
                    status=CASE WHEN attempts>=max_attempts THEN 'DEAD' ELSE 'PENDING' END,
                    last_error_code=CASE WHEN attempts>=max_attempts THEN 'ATTEMPTS_EXHAUSTED' ELSE last_error_code END,
                    finished_at=CASE WHEN attempts>=max_attempts THEN ? ELSE NULL END,
                    locked_until=CASE WHEN attempts>=max_attempts THEN NULL ELSE ? END,
                    claim_token=CASE WHEN attempts>=max_attempts THEN NULL ELSE ? END,
                    attempts=CASE WHEN attempts>=max_attempts THEN attempts ELSE attempts+1 END
                FROM candidates c WHERE o.operation_id=c.operation_id
                RETURNING o.*
                """;
        try (var c = fresh(); var ps = statement(c, sql, kind.name(), current, current, limit,
                current, until, token); var rs = ps.executeQuery()) {
            List<Claim> claims = new ArrayList<>();
            int exhausted = 0;
            while (rs.next()) {
                if ("DEAD".equals(rs.getString("status"))) {
                    exhausted++;
                } else {
                    claims.add(new Claim(rs.getObject("operation_id", UUID.class),
                            new TenantId(rs.getObject("tenant_id", UUID.class)),
                            new FileId(rs.getString("file_id")), new VersionId(rs.getObject("version_id", UUID.class)),
                            new LocationId(rs.getObject("location_id", UUID.class)), kind,
                            rs.getObject("correlation_id", UUID.class), rs.getObject("claim_token", UUID.class),
                            rs.getObject("locked_until", OffsetDateTime.class).toInstant(), rs.getInt("attempts")));
                }
            }
            return new Batch(claims, exhausted);
        } catch (SQLException ex) {
            throw JdbcTransactions.translate(ex);
        }
    }

    public boolean stillOwned(Claim claim) {
        Objects.requireNonNull(claim);
        try (var c = fresh(); var ps = statement(c, """
                SELECT 1 FROM fs.storage_operation WHERE operation_id=? AND tenant_id=?
                    AND claim_token=? AND status='PENDING' AND locked_until>?
                """, claim.operation(), claim.tenant().value(), claim.token(), time(clock.instant()));
             var rs = ps.executeQuery()) {
            return rs.next();
        } catch (SQLException ex) {
            throw JdbcTransactions.translate(ex);
        }
    }

    /**
     * Finish only while the claim is active. Future handlers that change metadata
     * must use the supplied-connection variant and throw/rollback on a lost claim.
     */
    public boolean finish(Claim claim, ErrorCode error) {
        try (var c = fresh()) {
            return finishStatement(c, claim, error);
        } catch (SQLException ex) {
            throw JdbcTransactions.translate(ex);
        }
    }

    public boolean finishOnConnection(Connection connection, Claim claim, ErrorCode error) throws SQLException {
        if (connection.getAutoCommit()) {
            throw new IllegalArgumentException("transaction required");
        }
        return finishStatement(connection, claim, error);
    }

    private boolean finishStatement(Connection connection, Claim claim, ErrorCode error) throws SQLException {
        Objects.requireNonNull(claim);
        var now = clock.instant();
        var current = time(now);
        String result = error == null ? "SUCCEEDED" : error == ErrorCode.PERMANENT ? "DEAD" : "PENDING";
        try (var ps = statement(connection, """
                UPDATE fs.storage_operation SET
                    status=CASE WHEN ?='PENDING' AND attempts>=max_attempts THEN 'DEAD' ELSE ? END,
                    last_error_code=?,
                    finished_at=CASE WHEN ?='PENDING' AND attempts<max_attempts THEN NULL ELSE ? END,
                    available_at=CASE WHEN ?='PENDING' AND attempts<max_attempts THEN ? ELSE available_at END,
                    claim_token=NULL, locked_until=NULL
                WHERE operation_id=? AND tenant_id=? AND claim_token=? AND status='PENDING' AND locked_until>?
                """, result, result, error == null ? null : error.name(), result, current,
                result, time(now.plus(retryDelay)), claim.operation(), claim.tenant().value(), claim.token(), current)) {
            return ps.executeUpdate() == 1;
        }
    }

    /** Bounded tenant-scoped dead-letter inspection; no unsafe automatic redrive. */
    public List<Dead> dead(TenantId tenant, int limit) {
        Objects.requireNonNull(tenant);
        bound(limit);
        try (var c = fresh(); var ps = statement(c, """
                SELECT operation_id, kind, file_id, version_id, attempts, last_error_code, finished_at
                FROM fs.storage_operation WHERE tenant_id=? AND status='DEAD'
                ORDER BY finished_at DESC, operation_id LIMIT ?
                """, tenant.value(), limit); var rs = ps.executeQuery()) {
            List<Dead> result = new ArrayList<>();
            while (rs.next()) {
                result.add(new Dead(rs.getObject(1, UUID.class), Kind.valueOf(rs.getString(2)),
                        new FileId(rs.getString(3)), new VersionId(rs.getObject(4, UUID.class)),
                        rs.getInt(5), rs.getString(6), rs.getObject(7, OffsetDateTime.class).toInstant()));
            }
            return List.copyOf(result);
        } catch (SQLException ex) {
            throw JdbcTransactions.translate(ex);
        }
    }

    private Connection fresh() throws SQLException {
        Connection c = dataSource.getConnection();
        try {
            if (!c.getAutoCommit()) throw new SQLException("fresh connection required");
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            return c;
        } catch (SQLException | RuntimeException ex) {
            c.close();
            throw ex;
        }
    }

    private void bound(int limit) {
        if (limit < 1 || limit > maxBatch) throw new IllegalArgumentException("invalid batch size");
    }

    private static OffsetDateTime time(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }

    private static PreparedStatement statement(Connection c, String sql, Object... parameters) throws SQLException {
        var ps = c.prepareStatement(sql);
        try {
            for (int i = 0; i < parameters.length; i++) ps.setObject(i + 1, parameters[i]);
            return ps;
        } catch (SQLException ex) {
            ps.close();
            throw ex;
        }
    }
}
