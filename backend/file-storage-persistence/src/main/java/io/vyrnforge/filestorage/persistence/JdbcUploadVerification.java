package io.vyrnforge.filestorage.persistence;

import io.vyrnforge.filestorage.application.FileApplication.Caller;
import io.vyrnforge.filestorage.application.FileApplication.Code;
import io.vyrnforge.filestorage.application.FileApplication.Failure;
import io.vyrnforge.filestorage.domain.StorageModel.SessionId;
import io.vyrnforge.platform.kernel.context.CorrelationContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/** Durable integrity request/evidence, not a scan verdict or availability transition.
 * Reuses the exact upload resource and local grant fence from durable transfer claims.
 */
public final class JdbcUploadVerification {
    private final JdbcUploadTransfers transfers;

    public JdbcUploadVerification(JdbcUploadTransfers transfers) {
        this.transfers = Objects.requireNonNull(transfers);
    }

    public record Job(JdbcUploadTransfers.Pin pin, String expectedSha256, Instant verifiedAt) {
        @Override public String toString() { return "Job[redacted]"; }
        public boolean matched() { return verifiedAt != null; }
    }

    /** Trusted caller after fresh SecuredFiles authorization. Replays preserve the first expected digest. */
    public Job request(Caller caller, SessionId session, String expectedSha256, CorrelationContext correlation) {
        requireHash(expectedSha256);
        return transfers.execute((c, events, audits) -> {
            var resource = transfers.resource(c, caller, session);
            transfers.lockCurrentGrant(c, resource);
            var pin = resource.pin();
            requireClaim(c, pin);
            Instant now = transfers.now();
            if (!now.isBefore(resource.sessionExpiry())) throw new Failure(Code.DENIED);
            try (var ps = transfers.statement(c, """
                    INSERT INTO fs.upload_integrity_verification(session_id,expected_sha256,requested_at)
                    VALUES (?,?,?) ON CONFLICT (session_id) DO NOTHING
                    """, session.value(), expectedSha256, now.atOffset(ZoneOffset.UTC))) {
                if (ps.executeUpdate() == 1)
                    transfers.record(events, audits, correlation, pin, "verification-requested");
            }
            var job = read(c, pin);
            if (!job.expectedSha256().equals(expectedSha256)) throw new Failure(Code.CONFLICT);
            return job;
        });
    }

    /** Only a trusted verifier supplies independently measured bytes. No provider I/O in this transaction. */
    public Job matched(Job candidate, long measuredSize, String measuredSha256, CorrelationContext correlation) {
        Objects.requireNonNull(candidate);
        requireHash(measuredSha256);
        if (measuredSize != candidate.pin().size() || !candidate.expectedSha256().equals(measuredSha256))
            throw new Failure(Code.INVALID);
        return transfers.execute((c, events, audits) -> {
            var resource = transfers.resource(c, candidate.pin().caller(), candidate.pin().session());
            transfers.lockCurrentGrant(c, resource);
            var pin = resource.pin();
            if (!pin.equals(candidate.pin())) throw new Failure(Code.CONFLICT);
            requireClaim(c, pin);
            var current = read(c, pin);
            if (!current.expectedSha256().equals(measuredSha256)) throw new Failure(Code.CONFLICT);
            if (current.matched()) return current;
            Instant now = transfers.now();
            if (!now.isBefore(resource.sessionExpiry())) throw new Failure(Code.DENIED);
            try (var ps = transfers.statement(c, """
                    UPDATE fs.upload_integrity_verification SET verified_at=?,observed_size=?,observed_sha256=?
                    WHERE session_id=? AND expected_sha256=? AND verified_at IS NULL
                    """, now.atOffset(ZoneOffset.UTC), measuredSize, measuredSha256, pin.session().value(), measuredSha256)) {
                if (ps.executeUpdate() != 1) throw new Failure(Code.CONFLICT);
            }
            transfers.record(events, audits, correlation, pin, "integrity-matched");
            return read(c, pin);
        });
    }

    private void requireClaim(Connection c, JdbcUploadTransfers.Pin pin) throws SQLException {
        try (var ps = transfers.statement(c, """
                SELECT 1 FROM fs.upload_transfer_handle
                WHERE tenant_id=? AND session_id=? AND actor=? AND consumer=? AND file_id=? AND version_id=?
                    AND backend_id=? AND config_revision=? AND location_id=? AND object_key=? AND expected_size=?
                    AND claimed_at IS NOT NULL FOR SHARE
                """, pin.caller().tenant().value(), pin.session().value(), pin.caller().actor(), pin.caller().consumer(),
                pin.file().value(), pin.version().value(), pin.backend().value(), pin.revision(),
                pin.locator().location().value(), pin.locator().objectKey(), pin.size()); var rows = ps.executeQuery()) {
            if (!rows.next()) throw new Failure(Code.DENIED);
        }
    }

    private Job read(Connection c, JdbcUploadTransfers.Pin pin) throws SQLException {
        try (var ps = transfers.statement(c, """
                SELECT expected_sha256,verified_at FROM fs.upload_integrity_verification WHERE session_id=?
                """, pin.session().value()); var rows = ps.executeQuery()) {
            if (!rows.next()) throw new Failure(Code.CONFLICT);
            var verified = rows.getObject(2, OffsetDateTime.class);
            return new Job(pin, rows.getString(1), verified == null ? null : verified.toInstant());
        }
    }

    private static void requireHash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new Failure(Code.INVALID);
    }
}
