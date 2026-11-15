package io.vyrnforge.filestorage.persistence;

import io.vyrnforge.filestorage.application.FileApplication.Caller;
import io.vyrnforge.filestorage.application.FileApplication.Code;
import io.vyrnforge.filestorage.application.FileApplication.Failure;
import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.platform.kernel.context.ActorReference;
import io.vyrnforge.platform.kernel.context.ActorType;
import io.vyrnforge.platform.kernel.context.CorrelationContext;
import io.vyrnforge.platform.kernel.persistence.jdbc.JacksonRecordCodec;
import io.vyrnforge.platform.kernel.persistence.jdbc.JdbcReliabilityTransaction;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import javax.sql.DataSource;

/** Internal durable upload claim repository. Call only after fresh secured authorization.
 * No provider I/O or external authority call within these transactions.
 */
public final class JdbcUploadTransfers {
    private final DataSource source;
    private final JdbcReliabilityTransaction transaction;
    private final Clock clock;
    private final Duration ttl;
    private final int queryTimeout;
    private final SecureRandom random = new SecureRandom();

    public JdbcUploadTransfers(DataSource source, Clock clock, Duration ttl, int queryTimeout) {
        this.source = Objects.requireNonNull(source);
        this.clock = Objects.requireNonNull(clock);
        this.ttl = Objects.requireNonNull(ttl);
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofHours(1)) > 0 || queryTimeout < 1)
            throw new IllegalArgumentException("invalid handle profile");
        this.queryTimeout = queryTimeout;
        transaction = new JdbcReliabilityTransaction(new KernelDataSource(source, Connection.TRANSACTION_READ_COMMITTED),
                new JacksonRecordCodec(), clock);
    }

    public record Lookup(TenantId tenant, SessionId session) {}
    public record Issued(String handle, Instant expiresAt) {
        @Override public String toString() { return "Issued[redacted]"; }
    }
    public record Pin(Caller caller, FileId file, VersionId version, SessionId session, BackendId backend,
                      long revision, Locator locator, long size, Instant expiresAt) {
        @Override public String toString() { return "Pin[redacted]"; }
    }
    private record Resource(Pin pin, Instant sessionExpiry, String purpose) {}

    public Issued issue(Caller caller, SessionId session, CorrelationContext correlation) {
        Objects.requireNonNull(caller); Objects.requireNonNull(session); Objects.requireNonNull(correlation);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String handle = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        byte[] hash = hash(handle);
        return execute((connection, events, audits) -> {
            Resource resource = resource(connection, caller, session);
            lockCurrentGrant(connection, resource);
            Instant now = now();
            Instant expiry = now.plus(ttl).truncatedTo(ChronoUnit.MICROS);
            if (resource.sessionExpiry().isBefore(expiry)) expiry = resource.sessionExpiry().truncatedTo(ChronoUnit.MICROS);
            if (!now.isBefore(expiry)) throw denied();
            Pin pin = resource.pin();
            try (var ps = statement(connection, """
                    INSERT INTO fs.upload_transfer_handle
                    (handle_hash,tenant_id,session_id,actor,consumer,file_id,version_id,location_id,backend_id,
                     config_revision,object_key,expected_size,expires_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT (session_id) DO NOTHING
                    """, hash, caller.tenant().value(), session.value(), caller.actor(), caller.consumer(),
                    pin.file().value(), pin.version().value(), pin.locator().location().value(), pin.backend().value(),
                    pin.revision(), pin.locator().objectKey(), pin.size(), expiry.atOffset(ZoneOffset.UTC))) {
                if (ps.executeUpdate() != 1) throw new Failure(Code.CONFLICT);
            }
            record(events, audits, correlation, pin, "issued");
            return new Issued(handle, expiry);
        });
    }

    /** Untrusted lookup only identifies what to reauthorize. Never sufficient for access. */
    public Lookup lookup(String handle) {
        try (var connection = source.getConnection();
             var ps = statement(connection, """
                     SELECT tenant_id,session_id FROM fs.upload_transfer_handle
                     WHERE handle_hash=? AND claimed_at IS NULL AND expires_at>?
                     """, hash(handle), now().atOffset(ZoneOffset.UTC)); var rows = ps.executeQuery()) {
            if (!rows.next()) throw denied();
            return new Lookup(new TenantId(rows.getObject(1, UUID.class)), new SessionId(rows.getObject(2, UUID.class)));
        } catch (SQLException ex) { throw new JdbcTransactions.PersistenceFailure(); }
    }

    public Pin claim(String handle, Caller caller, SessionId session, CorrelationContext correlation) {
        byte[] hash = hash(handle);
        return execute((connection, events, audits) -> {
            // Lock current session/resource/revision before consuming; concurrent claims serialize on session.
            var resource = resource(connection, caller, session);
            lockCurrentGrant(connection, resource);
            Pin pin = resource.pin();
            Instant now = now();
            try (var ps = statement(connection, """
                    UPDATE fs.upload_transfer_handle SET claimed_at=?
                    WHERE handle_hash=? AND tenant_id=? AND session_id=? AND actor=? AND consumer=?
                      AND file_id=? AND version_id=? AND location_id=? AND backend_id=? AND config_revision=?
                      AND object_key=? AND expected_size=? AND claimed_at IS NULL AND expires_at>?
                    RETURNING expires_at
                    """, now.atOffset(ZoneOffset.UTC), hash, caller.tenant().value(), session.value(), caller.actor(),
                    caller.consumer(), pin.file().value(), pin.version().value(), pin.locator().location().value(),
                    pin.backend().value(), pin.revision(), pin.locator().objectKey(), pin.size(), now.atOffset(ZoneOffset.UTC));
                 var rows = ps.executeQuery()) {
                if (!rows.next()) throw denied();
                Instant expiry = rows.getObject(1, OffsetDateTime.class).toInstant();
                if (resource.sessionExpiry().isBefore(expiry)) expiry = resource.sessionExpiry();
                if (!clock.instant().isBefore(expiry)) throw denied();
                Pin claimed = new Pin(caller, pin.file(), pin.version(), session, pin.backend(), pin.revision(),
                        pin.locator(), pin.size(), expiry);
                record(events, audits, correlation, claimed, "claimed");
                return claimed;
            }
        });
    }

    private Resource resource(Connection connection, Caller caller, SessionId session) throws SQLException {
        try (var ps = statement(connection, """
                SELECT s.file_id,s.version_id,s.location_id,s.backend_id,s.config_revision,v.object_key,
                       r.expected_size,s.expires_at,s.expires_nano_remainder,s.purpose
                FROM fs.upload_session s
                JOIN fs.file f ON f.tenant_id=s.tenant_id AND f.file_id=s.file_id
                JOIN fs.file_version v ON v.tenant_id=s.tenant_id AND v.file_id=s.file_id
                    AND v.version_id=s.version_id AND v.location_id=s.location_id
                JOIN fs.initiation_request r ON r.tenant_id=s.tenant_id AND r.session_id=s.session_id AND r.consumer=s.consumer
                JOIN fs.backend_revision b ON b.backend_id=s.backend_id AND b.config_revision=s.config_revision
                WHERE s.tenant_id=? AND s.session_id=? AND s.consumer=? AND r.actor=?
                    AND s.state='INITIATED' AND f.state='ACTIVE' AND v.state='PENDING'
                    AND b.status IN ('ACTIVE','DRAINING') AND b.validated_revision=b.config_revision
                FOR UPDATE OF s FOR SHARE OF f,v,r,b
                """, caller.tenant().value(), session.value(), caller.consumer(), caller.actor());
             var rows = ps.executeQuery()) {
            if (!rows.next()) throw denied();
            Instant expiry = rows.getObject(8, OffsetDateTime.class).toInstant().plusNanos(rows.getInt(9));
            if (!clock.instant().isBefore(expiry)) throw denied();
            var pin = new Pin(caller, new FileId(rows.getString(1)), new VersionId(rows.getObject(2, UUID.class)),
                    session, new BackendId(rows.getObject(4, UUID.class)), rows.getLong(5),
                    new Locator(new LocationId(rows.getObject(3, UUID.class)), rows.getString(6), null),
                    rows.getLong(7), expiry);
            return new Resource(pin, expiry, rows.getString(10));
        }
    }

    /** Same-database grant revocation is fenced until commit. External IAM is still check-time. */
    private void lockCurrentGrant(Connection connection, Resource resource) throws SQLException {
        Pin pin = resource.pin();
        Caller caller = pin.caller();
        Instant now = now();
        try (var ps = statement(connection, """
                SELECT expires_at FROM fs.file_grant
                WHERE tenant_id=? AND actor=? AND consumer=? AND purpose=? AND action='UPLOAD_TRANSFER'
                  AND NOT revoked AND not_before<=? AND expires_at>?
                  AND (scope='OWNED_FILE' OR (scope='EXACT_FILE' AND file_id=?))
                ORDER BY grant_id LIMIT 1 FOR SHARE
                """, caller.tenant().value(), caller.actor(), caller.consumer(), resource.purpose(),
                now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC), pin.file().value());
             var rows = ps.executeQuery()) {
            if (!rows.next() || !clock.instant().isBefore(rows.getObject(1, OffsetDateTime.class).toInstant()))
                throw denied();
        }
    }

    private void record(io.vyrnforge.platform.kernel.event.EventIntentPort events,
            io.vyrnforge.platform.kernel.audit.AuditIntentPort audits, CorrelationContext correlation, Pin pin, String action) {
        MutationIntents.record(events, audits, clock, correlation,
                new ActorReference(ActorType.USER, pin.caller().actor(), null, null), pin.caller().tenant().value(),
                "file-storage.transfer.upload."+action, "file-storage.file", pin.file().value(),
                Map.of("sessionId", pin.session().value().toString(), "versionId", pin.version().value().toString()));
    }

    private PreparedStatement statement(Connection connection, String sql, Object... parameters) throws SQLException {
        var ps = connection.prepareStatement(sql);
        try {
            ps.setQueryTimeout(queryTimeout);
            for (int i=0; i<parameters.length; i++) ps.setObject(i+1, parameters[i]);
            return ps;
        } catch (SQLException ex) { ps.close(); throw ex; }
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static Failure denied() { return new Failure(Code.DENIED); }
    private static byte[] hash(String handle) {
        if (handle == null || !handle.matches("[A-Za-z0-9_-]{43}")) throw denied();
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(handle);
            if (decoded.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(handle))
                throw denied();
            return MessageDigest.getInstance("SHA-256").digest(decoded);
        } catch (IllegalArgumentException ex) { throw denied(); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("digest unavailable"); }
    }

    @FunctionalInterface private interface Work<T> {
        T run(Connection connection, io.vyrnforge.platform.kernel.event.EventIntentPort events,
                io.vyrnforge.platform.kernel.audit.AuditIntentPort audits) throws SQLException;
    }
    private <T> T execute(Work<T> work) {
        var failure = new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
        try {
            return transaction.execute((connection, events, audits) -> {
                try { return work.run(connection, events, audits); }
                catch (SQLException ex) { var translated = JdbcTransactions.translate(ex); failure.set(translated); throw translated; }
                catch (RuntimeException ex) { failure.set(ex); throw ex; }
            });
        } catch (RuntimeException ex) {
            if (failure.get() != null) throw failure.get();
            throw new JdbcTransactions.PersistenceFailure();
        }
    }
}
