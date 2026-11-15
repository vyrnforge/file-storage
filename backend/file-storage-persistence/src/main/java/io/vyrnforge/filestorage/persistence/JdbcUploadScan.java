package io.vyrnforge.filestorage.persistence;

import io.vyrnforge.filestorage.application.FileApplication.Caller;
import io.vyrnforge.filestorage.application.FileApplication.Code;
import io.vyrnforge.filestorage.application.FileApplication.Failure;
import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.platform.kernel.context.ActorReference;
import io.vyrnforge.platform.kernel.context.CorrelationContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Durable scan lifecycle. Trusted commands only; external I/O never occurs in these transactions. */
public final class JdbcUploadScan {
    private final JdbcUploadTransfers transfers;
    private final JdbcOperations operations;
    private final ActorReference worker;
    private final int maxAttempts;

    public JdbcUploadScan(JdbcUploadTransfers transfers, JdbcOperations operations,
                          ActorReference worker, int maxAttempts) {
        this.transfers = Objects.requireNonNull(transfers);
        this.operations = Objects.requireNonNull(operations);
        this.worker = Objects.requireNonNull(worker);
        if (maxAttempts < 1) throw new IllegalArgumentException("invalid scan attempts");
        this.maxAttempts = maxAttempts;
    }

    public record Job(JdbcOperations.Claim claim, JdbcUploadTransfers.Pin pin,
                      String digest, String declaredMime, String purpose) {
        @Override public String toString() { return "ScanJob[redacted]"; }
    }

    /** After fresh secured completion authorization, enqueue and move both states atomically. */
    public UUID begin(Caller caller, SessionId session, CorrelationContext correlation) {
        return transfers.execute((c, events, audits) -> begin(c, events, audits, caller, session, correlation));
    }

    UUID begin(Connection c, io.vyrnforge.platform.kernel.event.EventIntentPort events,
               io.vyrnforge.platform.kernel.audit.AuditIntentPort audits, Caller caller, SessionId session,
               CorrelationContext correlation) throws SQLException {
            boolean verifying;
            try (var ps = transfers.statement(c, "SELECT state FROM fs.upload_session WHERE tenant_id=? AND session_id=? FOR UPDATE",
                    caller.tenant().value(), session.value()); var rows = ps.executeQuery()) {
                if (!rows.next()) throw new Failure(Code.DENIED);
                verifying = "VERIFYING".equals(rows.getString(1));
            }
            var resource = transfers.resource(c, caller, session, verifying);
            transfers.lockCurrentGrant(c, resource);
            var pin = resource.pin();
            try (var ps = transfers.statement(c, """
                    SELECT 1 FROM fs.upload_integrity_verification i JOIN fs.upload_transfer_handle h USING(session_id)
                    WHERE i.session_id=? AND i.verified_at IS NOT NULL AND i.observed_size=?
                      AND h.tenant_id=? AND h.actor=? AND h.consumer=? AND h.file_id=? AND h.version_id=?
                      AND h.backend_id=? AND h.config_revision=? AND h.location_id=? AND h.object_key=?
                      AND h.expected_size=i.observed_size AND h.claimed_at IS NOT NULL FOR SHARE OF i,h
                    """, session.value(), pin.size(), caller.tenant().value(), caller.actor(), caller.consumer(),
                    pin.file().value(),pin.version().value(),pin.backend().value(),pin.revision(),
                    pin.locator().location().value(),pin.locator().objectKey()); var rows = ps.executeQuery()) {
                if (!rows.next()) throw new Failure(Code.CONFLICT);
            }
            var id = operations.enqueue(c, new JdbcOperations.Request(caller.tenant(), pin.file(), pin.version(),
                    pin.locator().location(), JdbcOperations.Kind.SCAN_UPLOAD, session.value().toString(),
                    correlation.correlationId(), maxAttempts));
            if (!verifying) {
                transition(c, pin, "PENDING", "VERIFYING", "INITIATED", "VERIFYING");
                transfers.record(events, audits, correlation, pin, "scan-queued");
            }
            return id;
    }

    public JdbcOperations.Batch claim(int limit) { return operations.claim(JdbcOperations.Kind.SCAN_UPLOAD, limit); }

    /** Load a fresh, exact, fenced work snapshot. A worker may inspect bytes only after this check. */
    public Job load(JdbcOperations.Claim claim) {
        return transfers.execute((c, events, audits) -> load(c, claim));
    }

    private Job load(Connection c, JdbcOperations.Claim claim) throws SQLException {
        if (claim.kind() != JdbcOperations.Kind.SCAN_UPLOAD) throw new Failure(Code.DENIED);
        try (var ps = transfers.statement(c, """
                SELECT s.session_id,s.backend_id,s.config_revision,v.object_key,r.actor,s.consumer,s.purpose,
                       s.expires_at,s.expires_nano_remainder,i.observed_size,i.observed_sha256,r.declared_mime_type
                FROM fs.storage_operation o
                JOIN fs.upload_session s ON s.tenant_id=o.tenant_id AND s.file_id=o.file_id
                    AND s.version_id=o.version_id AND s.location_id=o.location_id AND s.session_id::text=o.operation_key
                JOIN fs.file f ON f.tenant_id=s.tenant_id AND f.file_id=s.file_id
                JOIN fs.file_version v ON v.tenant_id=s.tenant_id AND v.file_id=s.file_id AND v.version_id=s.version_id
                JOIN fs.initiation_request r ON r.tenant_id=s.tenant_id AND r.session_id=s.session_id
                JOIN fs.upload_integrity_verification i ON i.session_id=s.session_id
                JOIN fs.upload_transfer_handle h ON h.session_id=s.session_id AND h.tenant_id=s.tenant_id
                    AND h.actor=r.actor AND h.consumer=s.consumer AND h.file_id=s.file_id AND h.version_id=s.version_id
                    AND h.backend_id=s.backend_id AND h.config_revision=s.config_revision
                    AND h.location_id=s.location_id AND h.object_key=v.object_key AND h.expected_size=i.observed_size
                    AND h.claimed_at IS NOT NULL
                JOIN fs.backend_revision b ON b.backend_id=s.backend_id AND b.config_revision=s.config_revision
                WHERE o.operation_id=? AND o.tenant_id=? AND o.file_id=? AND o.version_id=? AND o.location_id=?
                  AND o.kind='SCAN_UPLOAD' AND o.status='PENDING' AND o.claim_token=? AND o.locked_until>?
                  AND s.state='VERIFYING' AND v.state='VERIFYING' AND f.state='ACTIVE'
                  AND b.status IN ('ACTIVE','DRAINING') AND b.validated_revision=b.config_revision
                  AND i.verified_at IS NOT NULL AND i.observed_size=r.expected_size
                FOR UPDATE OF s,v FOR SHARE OF f,r,i,b,h
                """, claim.operation(), claim.tenant().value(), claim.file().value(), claim.version().value(),
                claim.location().value(), claim.token(), transfers.now().atOffset(ZoneOffset.UTC));
             var rows = ps.executeQuery()) {
            if (!rows.next()) throw new Failure(Code.CONFLICT);
            Instant expiry = rows.getObject(8,OffsetDateTime.class).toInstant().plusNanos(rows.getInt(9));
            var pin = new JdbcUploadTransfers.Pin(new Caller(claim.tenant(),rows.getString(5),rows.getString(6)),
                    claim.file(),claim.version(),new SessionId(rows.getObject(1,UUID.class)),
                    new BackendId(rows.getObject(2,UUID.class)),rows.getLong(3),
                    new Locator(claim.location(),rows.getString(4),null),rows.getLong(10),expiry);
            return new Job(claim,pin,rows.getString(11),rows.getString(12),rows.getString(7));
        }
    }

    /** Only a trusted scanner orchestrator supplies this independently measured verdict. */
    public void finish(Job candidate, Content measured, Scan verdict) {
        Objects.requireNonNull(measured); Objects.requireNonNull(verdict);
        if (verdict == Scan.UNAVAILABLE || measured.size()!=candidate.pin().size()
                || !measured.sha256().equals(candidate.digest())) throw new Failure(Code.INVALID);
        transfers.execute((c,events,audits)->{
            Job current=load(c,candidate.claim());
            if (!current.equals(candidate)) throw new Failure(Code.CONFLICT);
            var pin=current.pin();
            Instant now=transfers.now();
            if (!now.isBefore(pin.expiresAt())) throw new Failure(Code.DENIED);
            if (verdict==Scan.CLEAN) {
                if (!current.declaredMime().equals(measured.mimeType())) throw new Failure(Code.INVALID);
                transfers.lockCurrentGrant(c,new JdbcUploadTransfers.Resource(pin,pin.expiresAt(),current.purpose()));
            }
            if (!operations.finishOnConnection(c,current.claim(),null)) throw new Failure(Code.CONFLICT);
            try(var ps=transfers.statement(c,"""
                    INSERT INTO fs.upload_scan_evidence(session_id,operation_id,verdict,observed_size,observed_sha256,detected_mime_type,scanned_at)
                    VALUES (?,?,?,?,?,?,?)
                    """,pin.session().value(),current.claim().operation(),verdict.name(),measured.size(),measured.sha256(),
                    measured.mimeType(),now.atOffset(ZoneOffset.UTC))){
                if(ps.executeUpdate()!=1)throw new Failure(Code.CONFLICT);
            }
            boolean clean=verdict==Scan.CLEAN;
            transition(c,pin,"VERIFYING",clean?"AVAILABLE":"QUARANTINED","VERIFYING",clean?"COMPLETED":"FAILED");
            record(events,audits,pin,current.claim().correlation(),clean?"completed":"quarantined");
            return null;
        });
    }

    /** Retryable errors release the lease; exhausted jobs stay unavailable until reconciliation. */
    public boolean retry(JdbcOperations.Claim claim) {
        if(claim.kind()!=JdbcOperations.Kind.SCAN_UPLOAD)throw new Failure(Code.INVALID);
        return operations.finish(claim,JdbcOperations.ErrorCode.TRANSIENT);
    }

    /** Permanent integrity/policy failure or expired session; fenced with the job terminal update. */
    public void fail(Job candidate) {
        transfers.execute((c,events,audits)->{
            Job current=load(c,candidate.claim());
            if(!current.equals(candidate)||!operations.finishOnConnection(c,current.claim(),JdbcOperations.ErrorCode.PERMANENT))
                throw new Failure(Code.CONFLICT);
            var pin=current.pin();
            boolean expired=!transfers.now().isBefore(pin.expiresAt());
            transition(c,pin,"VERIFYING","FAILED","VERIFYING",expired?"EXPIRED":"FAILED");
            record(events,audits,pin,current.claim().correlation(),expired?"expired":"failed");
            return null;
        });
    }

    /** Explicit tenant/session-scoped recovery of a dead job (including a crashed last attempt). */
    public boolean reconcileDead(TenantId tenant, SessionId session) {
        return transfers.execute((c,events,audits)->{
            try(var ps=transfers.statement(c,"""
                    SELECT s.file_id,s.version_id,s.location_id,s.backend_id,s.config_revision,v.object_key,
                        r.actor,s.consumer,r.expected_size,s.expires_at,s.expires_nano_remainder,o.correlation_id
                    FROM fs.upload_session s
                    JOIN fs.file_version v ON v.version_id=s.version_id AND v.tenant_id=s.tenant_id
                    JOIN fs.initiation_request r ON r.session_id=s.session_id AND r.tenant_id=s.tenant_id
                    JOIN fs.storage_operation o ON o.tenant_id=s.tenant_id AND o.file_id=s.file_id
                        AND o.version_id=s.version_id AND o.location_id=s.location_id AND o.operation_key=s.session_id::text
                    WHERE s.tenant_id=? AND s.session_id=? AND s.state='VERIFYING' AND v.state='VERIFYING'
                      AND o.kind='SCAN_UPLOAD' AND o.status='DEAD'
                    FOR UPDATE OF s,v
                    """,tenant.value(),session.value());var rows=ps.executeQuery()){
                if(!rows.next())return false;
                Instant expiry=rows.getObject(10,OffsetDateTime.class).toInstant().plusNanos(rows.getInt(11));
                var pin=new JdbcUploadTransfers.Pin(new Caller(tenant,rows.getString(7),rows.getString(8)),
                    new FileId(rows.getString(1)),new VersionId(rows.getObject(2,UUID.class)),session,
                    new BackendId(rows.getObject(4,UUID.class)),rows.getLong(5),
                    new Locator(new LocationId(rows.getObject(3,UUID.class)),rows.getString(6),null),rows.getLong(9),expiry);
                boolean expired=!transfers.now().isBefore(expiry);
                transition(c,pin,"VERIFYING","FAILED","VERIFYING",expired?"EXPIRED":"FAILED");
                record(events,audits,pin,rows.getObject(12,UUID.class),expired?"expired":"failed");
                return true;
            }
        });
    }

    private void transition(Connection c,JdbcUploadTransfers.Pin pin,String fromVersion,String toVersion,
                            String fromSession,String toSession)throws SQLException{
        try(var ps=transfers.statement(c,"UPDATE fs.file_version SET state=? WHERE tenant_id=? AND version_id=? AND state=?",
                toVersion,pin.caller().tenant().value(),pin.version().value(),fromVersion)){
            if(ps.executeUpdate()!=1)throw new Failure(Code.CONFLICT);
        }
        try(var ps=transfers.statement(c,"UPDATE fs.upload_session SET state=? WHERE tenant_id=? AND session_id=? AND state=?",
                toSession,pin.caller().tenant().value(),pin.session().value(),fromSession)){
            if(ps.executeUpdate()!=1)throw new Failure(Code.CONFLICT);
        }
    }

    private void record(io.vyrnforge.platform.kernel.event.EventIntentPort events,
                        io.vyrnforge.platform.kernel.audit.AuditIntentPort audits,
                        JdbcUploadTransfers.Pin pin,UUID originalCorrelation,String action){
        MutationIntents.record(events,audits,java.time.Clock.fixed(transfers.now(),ZoneOffset.UTC),
                CorrelationContext.create(),worker,pin.caller().tenant().value(),
                "file-storage.upload."+action,"file-storage.file",pin.file().value(),
                Map.of("sessionId",pin.session().value().toString(),"versionId",pin.version().value().toString(),
                    "originalCorrelationId",originalCorrelation.toString()));
    }
}
