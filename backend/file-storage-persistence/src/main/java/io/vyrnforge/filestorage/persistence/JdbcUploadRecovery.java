package io.vyrnforge.filestorage.persistence;

import io.vyrnforge.filestorage.application.FileApplication.Code;
import io.vyrnforge.filestorage.application.FileApplication.Failure;
import io.vyrnforge.platform.kernel.context.ActorReference;
import io.vyrnforge.platform.kernel.context.CorrelationContext;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Bounded trusted recovery sweep. No provider I/O, deletion, reissue or end-user token. */
public final class JdbcUploadRecovery {
    private final JdbcUploadTransfers transfers;
    private final ActorReference worker;

    public JdbcUploadRecovery(JdbcUploadTransfers transfers,ActorReference worker){
        this.transfers=Objects.requireNonNull(transfers);
        this.worker=Objects.requireNonNull(worker);
    }

    /** One exact session per transaction, shared session lock serializes with claims and completion. */
    public boolean runOne(){
        return transfers.execute((c,events,audits)->{
            var now=transfers.now();
            var timestamp=now.atOffset(ZoneOffset.UTC);
            UUID tenant,session,version,location;
            String file,state,reason;
            try(var ps=transfers.statement(c,"""
                    SELECT s.tenant_id,s.session_id,s.file_id,s.version_id,s.location_id,s.state,
                        CASE WHEN s.expires_at<? OR (s.expires_at=? AND s.expires_nano_remainder=0)
                             THEN 'SESSION_EXPIRED'
                             WHEN s.state='INITIATED' THEN 'VERIFY_DEAD' ELSE 'SCAN_DEAD' END
                    FROM fs.upload_session s
                    JOIN fs.file_version v ON v.tenant_id=s.tenant_id AND v.file_id=s.file_id AND v.version_id=s.version_id
                    WHERE ((s.state='INITIATED' AND v.state='PENDING') OR (s.state='VERIFYING' AND v.state='VERIFYING'))
                      AND (s.expires_at<? OR (s.expires_at=? AND s.expires_nano_remainder=0) OR EXISTS (
                          SELECT 1 FROM fs.storage_operation o
                          WHERE o.tenant_id=s.tenant_id AND o.file_id=s.file_id AND o.version_id=s.version_id
                            AND o.location_id=s.location_id AND o.operation_key=s.session_id::text AND o.status='DEAD'
                            AND ((s.state='INITIATED' AND o.kind='VERIFY_UPLOAD') OR (s.state='VERIFYING' AND o.kind='SCAN_UPLOAD'))
                      ))
                    ORDER BY s.expires_at,s.session_id LIMIT 1 FOR UPDATE OF s SKIP LOCKED
                    """,timestamp,timestamp,timestamp,timestamp);var rows=ps.executeQuery()){
                if(!rows.next())return false;
                tenant=rows.getObject(1,UUID.class);session=rows.getObject(2,UUID.class);file=rows.getString(3);
                version=rows.getObject(4,UUID.class);location=rows.getObject(5,UUID.class);
                state=rows.getString(6);reason=rows.getString(7);
            }
            // Session expiry invalidates all pending verification/scan claims, including active leases.
            // A worker may still be reading remotely, but cannot commit any metadata result.
            if("SESSION_EXPIRED".equals(reason)){
                try(var ps=transfers.statement(c,"""
                        UPDATE fs.storage_operation SET status='DEAD',last_error_code='PERMANENT',
                            finished_at=?,claim_token=NULL,locked_until=NULL
                        WHERE tenant_id=? AND file_id=? AND version_id=? AND location_id=? AND operation_key=?
                          AND kind IN ('VERIFY_UPLOAD','SCAN_UPLOAD') AND status='PENDING'
                        """,timestamp,tenant,file,version,location,session.toString())){
                    ps.executeUpdate();
                }
            }
            try(var ps=transfers.statement(c,"""
                    INSERT INTO fs.upload_terminal_recovery(session_id,reason,recovered_at) VALUES (?,?,?)
                    """,session,reason,timestamp)){
                if(ps.executeUpdate()!=1)throw new Failure(Code.CONFLICT);
            }
            try(var ps=transfers.statement(c,"""
                    UPDATE fs.file_version SET state='FAILED' WHERE tenant_id=? AND file_id=? AND version_id=? AND state=?
                    """,tenant,file,version,"INITIATED".equals(state)?"PENDING":"VERIFYING")){
                if(ps.executeUpdate()!=1)throw new Failure(Code.CONFLICT);
            }
            String terminal="SESSION_EXPIRED".equals(reason)?"EXPIRED":"FAILED";
            try(var ps=transfers.statement(c,"UPDATE fs.upload_session SET state=? WHERE tenant_id=? AND session_id=? AND state=?",
                    terminal,tenant,session,state)){
                if(ps.executeUpdate()!=1)throw new Failure(Code.CONFLICT);
            }
            MutationIntents.record(events,audits,Clock.fixed(now,ZoneOffset.UTC),CorrelationContext.create(),worker,tenant,
                    "file-storage.upload.recovered","file-storage.file",file,
                    Map.of("sessionId",session.toString(),"versionId",version.toString(),"reason",reason,
                        "cleanupDisposition","HOLD"));
            return true;
        });
    }
}
