package io.vyrnforge.filestorage.persistence;

import io.vyrnforge.filestorage.application.FileApplication.*;
import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.platform.kernel.context.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;

/** Reversible logical state only. No external authority or provider I/O in the transaction. */
final class JdbcFileLifecycle {
    private final JdbcUploadTransfers tx;
    private final Clock clock;

    JdbcFileLifecycle(DataSource source,Clock clock) {
        this.clock=clock;
        tx=new JdbcUploadTransfers(source,clock,Duration.ofMinutes(1),5);
    }

    LifecycleView change(Caller caller,FileId id,Action action,long expected,List<CatalogGrant> grants,
                         CorrelationContext correlation) {
        if(expected<0||expected==Long.MAX_VALUE||grants.isEmpty()||grants.size()>256
                ||(action!=Action.DELETE_FILE&&action!=Action.RESTORE_FILE))throw new Failure(Code.INVALID);
        return tx.execute((c,events,audits)->{
            // The file lock serializes against both issuance and claim of download handles.
            File file;
            long revision;
            try(var ps=tx.statement(c,"""
                SELECT name,state,lifecycle_revision FROM fs.file
                WHERE tenant_id=? AND file_id=? FOR UPDATE
                """,caller.tenant().value(),id.value());var rows=ps.executeQuery()){
                if(!rows.next())throw missing();
                file=new File(id,caller.tenant(),rows.getString(1),FileState.valueOf(rows.getString(2)));
                revision=rows.getLong(3);
            }
            String purpose;
            boolean owner;
            boolean eligible;
            try(var ps=tx.statement(c,"""
                SELECT s.purpose,r.actor,s.consumer,v.state,s.state,e.verdict
                FROM fs.upload_session s JOIN fs.file_version v
                    ON v.tenant_id=s.tenant_id AND v.file_id=s.file_id AND v.version_id=s.version_id
                JOIN fs.initiation_request r ON r.tenant_id=s.tenant_id AND r.session_id=s.session_id AND r.consumer=s.consumer
                LEFT JOIN fs.upload_scan_evidence e ON e.session_id=s.session_id
                WHERE s.tenant_id=? AND s.file_id=? FOR SHARE OF s,v,r
                """,caller.tenant().value(),id.value());var rows=ps.executeQuery()){
                if(!rows.next())throw missing();
                purpose=rows.getString(1);
                owner=caller.actor().equals(rows.getString(2))&&caller.consumer().equals(rows.getString(3));
                eligible="AVAILABLE".equals(rows.getString(4))&&"COMPLETED".equals(rows.getString(5))
                    &&"CLEAN".equals(rows.getString(6));
                if(rows.next())throw new Failure(Code.CONFLICT);
            }
            // External scope is a request-time trusted snapshot; local grants are rechecked and locked.
            var now=tx.now();
            if(!grants.stream().anyMatch(g->g.purpose().equals(purpose)&&g.expiresAt().isAfter(now)
                    &&(id.equals(g.file())||(g.file()==null&&owner))))throw missing();
            try(var ps=tx.statement(c,"""
                SELECT expires_at FROM fs.file_grant
                WHERE tenant_id=? AND actor=? AND consumer=? AND purpose=? AND action=?
                  AND NOT revoked AND not_before<=? AND expires_at>?
                  AND ((scope='OWNED_FILE' AND ?) OR (scope='EXACT_FILE' AND file_id=?))
                ORDER BY grant_id LIMIT 1 FOR SHARE
                """,caller.tenant().value(),caller.actor(),caller.consumer(),purpose,action.name(),
                now.atOffset(ZoneOffset.UTC),now.atOffset(ZoneOffset.UTC),owner,id.value());var rows=ps.executeQuery()){
                if(!rows.next()||!clock.instant().isBefore(rows.getObject(1,OffsetDateTime.class).toInstant()))throw missing();
            }
            if(!eligible)throw new Failure(Code.CONFLICT);
            FileState target=action==Action.DELETE_FILE?FileState.SOFT_DELETED:FileState.ACTIVE;
            // Retry of the immediately preceding successful transition is read-only.
            if(revision==expected+1&&file.state()==target)return new LifecycleView(file.reference(),target,revision);
            if(revision!=expected||file.state()==target)throw new Failure(Code.CONFLICT);
            File changed=action==Action.DELETE_FILE?file.softDelete():file.restore();
            long next=Math.incrementExact(revision);
            try(var ps=tx.statement(c,"""
                UPDATE fs.file SET state=?,lifecycle_revision=?,deleted_at=?
                WHERE tenant_id=? AND file_id=? AND lifecycle_revision=?
                """,changed.state().name(),next,target==FileState.SOFT_DELETED?now.atOffset(ZoneOffset.UTC):null,
                caller.tenant().value(),id.value(),revision)){
                if(ps.executeUpdate()!=1)throw new Failure(Code.CONFLICT);
            }
            MutationIntents.record(events,audits,clock,correlation,new ActorReference(ActorType.USER,caller.actor(),null,null),
                caller.tenant().value(),action==Action.DELETE_FILE?"file-storage.file.soft-deleted":"file-storage.file.restored",
                "file-storage.file",id.value(),Map.of("lifecycleRevision",next,"state",target.name()));
            return new LifecycleView(changed.reference(),target,next);
        });
    }

    private static Failure missing(){return new Failure(Code.NOT_FOUND);}
}
