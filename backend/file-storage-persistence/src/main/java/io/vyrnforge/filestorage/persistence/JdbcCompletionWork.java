package io.vyrnforge.filestorage.persistence;

import io.vyrnforge.filestorage.application.FileApplication.*;
import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.platform.kernel.context.CorrelationContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/** Atomic request + VERIFY_UPLOAD enqueue, and evidence + scan handoff. No external I/O. */
public final class JdbcCompletionWork {
    private final JdbcUploadTransfers transfers;
    private final JdbcUploadVerification verification;
    private final JdbcUploadScan scans;
    private final JdbcOperations operations;
    private final int attempts;

    public JdbcCompletionWork(JdbcUploadTransfers transfers, JdbcOperations operations,
                              JdbcUploadScan scans, int attempts) {
        this.transfers=Objects.requireNonNull(transfers);
        this.verification=new JdbcUploadVerification(transfers);
        this.operations=Objects.requireNonNull(operations);
        this.scans=Objects.requireNonNull(scans);
        if(attempts<1)throw new IllegalArgumentException("invalid attempts");
        this.attempts=attempts;
    }

    public record Accepted(SessionId session, UUID operation, String status, UploadState uploadState, VersionState versionState) {}
    public record Work(JdbcOperations.Claim claim, JdbcUploadVerification.Job job) {
        @Override public String toString(){return "CompletionWork[redacted]";}
    }

    /** Request identity is session plus first immutable digest; never enqueue a second job. */
    public Accepted request(Caller caller,SessionId session,String digest,CorrelationContext correlation) {
        if(digest==null||!digest.matches("[0-9a-f]{64}"))throw new Failure(Code.INVALID);
        return transfers.execute((c,events,audits)->{
            // Serialize with scan transition and handle claim. Replay existing work even during scan.
            String state;
            try(var ps=transfers.statement(c,"SELECT state FROM fs.upload_session WHERE tenant_id=? AND session_id=? FOR UPDATE",
                    caller.tenant().value(),session.value());var rows=ps.executeQuery()){
                if(!rows.next())throw new Failure(Code.DENIED);
                state=rows.getString(1);
            }
            if (java.util.Set.of("COMPLETED","FAILED","EXPIRED").contains(state))
                return terminal(c,caller,session,digest);
            boolean verifying="VERIFYING".equals(state);
            var resource=transfers.resource(c,caller,session,verifying);
            transfers.lockCurrentGrant(c,resource);
            var pin=resource.pin();
            verification.requireClaim(c,pin);
            if(verifying){
                var existing=verification.read(c,pin);
                if(!existing.expectedSha256().equals(digest))throw new Failure(Code.CONFLICT);
                return existing(c,pin);
            }
            var job=verification.request(c,events,audits,caller,session,digest,correlation);
            operations.enqueue(c,new JdbcOperations.Request(caller.tenant(),pin.file(),pin.version(),
                    pin.locator().location(),JdbcOperations.Kind.VERIFY_UPLOAD,session.value().toString(),
                    correlation.correlationId(),attempts));
            return existing(c,job.pin());
        });
    }

    private Accepted existing(Connection c,JdbcUploadTransfers.Pin pin)throws SQLException{
        try(var ps=transfers.statement(c,"""
                SELECT o.operation_id,o.status,s.state,v.state FROM fs.storage_operation o
                JOIN fs.upload_session s ON s.tenant_id=o.tenant_id AND s.file_id=o.file_id
                    AND s.version_id=o.version_id AND s.location_id=o.location_id AND s.session_id::text=o.operation_key
                JOIN fs.file_version v ON v.tenant_id=s.tenant_id AND v.version_id=s.version_id
                WHERE o.tenant_id=? AND o.file_id=? AND o.version_id=?
                  AND o.location_id=? AND o.kind='VERIFY_UPLOAD' AND o.operation_key=?
                """,pin.caller().tenant().value(),pin.file().value(),pin.version().value(),pin.locator().location().value(),
                pin.session().value().toString());var rows=ps.executeQuery()){
            if(!rows.next())throw new Failure(Code.CONFLICT);
            return new Accepted(pin.session(),rows.getObject(1,UUID.class),rows.getString(2),
                    UploadState.valueOf(rows.getString(3)),VersionState.valueOf(rows.getString(4)));
        }
    }

    /** Read-only terminal replay. No new job or provider resolution, no expiry extension. */
    private Accepted terminal(Connection c,Caller caller,SessionId session,String digest)throws SQLException{
        try(var ps=transfers.statement(c,"""
                SELECT s.file_id,s.version_id,s.location_id,s.backend_id,s.config_revision,v.object_key,
                       h.expected_size,s.expires_at,s.expires_nano_remainder,s.purpose,i.expected_sha256
                FROM fs.upload_session s
                JOIN fs.file f ON f.tenant_id=s.tenant_id AND f.file_id=s.file_id
                JOIN fs.file_version v ON v.tenant_id=s.tenant_id AND v.file_id=s.file_id
                    AND v.version_id=s.version_id AND v.location_id=s.location_id
                JOIN fs.initiation_request r ON r.tenant_id=s.tenant_id AND r.session_id=s.session_id AND r.consumer=s.consumer
                JOIN fs.upload_transfer_handle h ON h.session_id=s.session_id AND h.tenant_id=s.tenant_id
                    AND h.actor=r.actor AND h.consumer=s.consumer AND h.file_id=s.file_id AND h.version_id=s.version_id
                    AND h.backend_id=s.backend_id AND h.config_revision=s.config_revision AND h.location_id=s.location_id
                    AND h.object_key=v.object_key AND h.expected_size=r.expected_size AND h.claimed_at IS NOT NULL
                JOIN fs.upload_integrity_verification i ON i.session_id=s.session_id
                WHERE s.tenant_id=? AND s.session_id=? AND r.actor=? AND s.consumer=? AND f.state='ACTIVE'
                    AND ((s.state='COMPLETED' AND v.state='AVAILABLE')
                      OR (s.state='FAILED' AND v.state IN ('FAILED','QUARANTINED'))
                      OR (s.state='EXPIRED' AND v.state='FAILED'))
                FOR SHARE OF f,v,r,h,i
                """,caller.tenant().value(),session.value(),caller.actor(),caller.consumer());var rows=ps.executeQuery()){
            if(!rows.next())throw new Failure(Code.DENIED);
            var expiry=rows.getObject(8,java.time.OffsetDateTime.class).toInstant().plusNanos(rows.getInt(9));
            var pin=new JdbcUploadTransfers.Pin(caller,new FileId(rows.getString(1)),new VersionId(rows.getObject(2,UUID.class)),
                session,new BackendId(rows.getObject(4,UUID.class)),rows.getLong(5),
                new Locator(new LocationId(rows.getObject(3,UUID.class)),rows.getString(6),null),rows.getLong(7),expiry);
            transfers.lockCurrentGrant(c,new JdbcUploadTransfers.Resource(pin,expiry,rows.getString(10)));
            if(!digest.equals(rows.getString(11)))throw new Failure(Code.CONFLICT);
            return existing(c,pin);
        }
    }

    public JdbcOperations.Batch claim(){return operations.claim(JdbcOperations.Kind.VERIFY_UPLOAD,1);}

    public Work load(JdbcOperations.Claim claim){
        return transfers.execute((c,events,audits)->load(c,claim));
    }

    private Work load(Connection c,JdbcOperations.Claim claim)throws SQLException{
        if(claim.kind()!=JdbcOperations.Kind.VERIFY_UPLOAD)throw new Failure(Code.DENIED);
        Caller caller;SessionId session;
        try(var ps=transfers.statement(c,"""
                SELECT s.session_id,r.actor,s.consumer
                FROM fs.storage_operation o JOIN fs.upload_session s ON s.tenant_id=o.tenant_id AND s.file_id=o.file_id
                    AND s.version_id=o.version_id AND s.location_id=o.location_id AND s.session_id::text=o.operation_key
                JOIN fs.initiation_request r ON r.tenant_id=s.tenant_id AND r.session_id=s.session_id
                WHERE o.operation_id=? AND o.tenant_id=? AND o.file_id=? AND o.version_id=? AND o.location_id=?
                    AND o.kind='VERIFY_UPLOAD' AND o.status='PENDING' AND o.claim_token=? AND o.locked_until>?
                """,claim.operation(),claim.tenant().value(),claim.file().value(),claim.version().value(),claim.location().value(),
                claim.token(),transfers.now().atOffset(ZoneOffset.UTC));var rows=ps.executeQuery()){
            if(!rows.next())throw new Failure(Code.CONFLICT);
            session=new SessionId(rows.getObject(1,UUID.class));
            caller=new Caller(claim.tenant(),rows.getString(2),rows.getString(3));
        }
        var resource=transfers.resource(c,caller,session);
        transfers.lockCurrentGrant(c,resource);
        verification.requireClaim(c,resource.pin());
        return new Work(claim,verification.read(c,resource.pin()));
    }

    /** A stale claim cannot commit evidence, finish verification or enqueue a scan. */
    public void finish(Work work,String measuredDigest){
        transfers.execute((c,events,audits)->{
            var current=load(c,work.claim());
            if(!current.job().pin().equals(work.job().pin())||
                    !current.job().expectedSha256().equals(work.job().expectedSha256()))throw new Failure(Code.CONFLICT);
            var correlation=CorrelationContext.create(work.claim().correlation());
            var pin=current.job().pin();
            verification.matched(c,events,audits,current.job(),pin.size(),measuredDigest,correlation);
            if(!operations.finishOnConnection(c,work.claim(),null))throw new Failure(Code.CONFLICT);
            scans.begin(c,events,audits,pin.caller(),pin.session(),correlation);
            return null;
        });
    }

    public boolean retry(JdbcOperations.Claim claim,boolean permanent){
        if(claim.kind()!=JdbcOperations.Kind.VERIFY_UPLOAD)throw new Failure(Code.INVALID);
        return operations.finish(claim,permanent?JdbcOperations.ErrorCode.PERMANENT:JdbcOperations.ErrorCode.TRANSIENT);
    }
}
