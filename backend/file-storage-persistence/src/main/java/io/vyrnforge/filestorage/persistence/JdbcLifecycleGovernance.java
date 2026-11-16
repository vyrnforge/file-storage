package io.vyrnforge.filestorage.persistence;

import io.vyrnforge.filestorage.application.FileApplication.Code;
import io.vyrnforge.filestorage.application.FileApplication.Failure;
import io.vyrnforge.filestorage.application.LifecycleGovernance;
import io.vyrnforge.filestorage.application.LifecycleGovernance.*;
import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.platform.kernel.context.*;
import io.vyrnforge.platform.kernel.ids.UuidV7;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;

/** Internal governance boundary. No HTTP/runtime wiring, policy activation or hold release. */
public final class JdbcLifecycleGovernance {
    private final JdbcUploadTransfers tx;
    private final Clock clock;
    private final Authority authority;
    private final DrainVerifier verifier;
    public JdbcLifecycleGovernance(DataSource source,Clock clock,Authority authority,DrainVerifier verifier){
        this.clock=Objects.requireNonNull(clock);this.authority=Objects.requireNonNull(authority);this.verifier=Objects.requireNonNull(verifier);
        tx=new JdbcUploadTransfers(source,clock,Duration.ofMinutes(1),5);
    }
    public long draft(ActorReference actor,TenantId tenant,PolicyDraft draft,CorrelationContext correlation){
        require(actor,tenant,null,Action.DRAFT_POLICY);
        return tx.execute((c,events,audits)->{
            // Serialize first and subsequent draft CAS in a tenant-scoped advisory lock.
            try(var ps=tx.statement(c,"SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text),3105))",tenant.value().toString());var rows=ps.executeQuery()){rows.next();}
            long current;
            try(var ps=tx.statement(c,"SELECT COALESCE(max(revision),0) FROM fs.retention_policy_draft WHERE tenant_id=?",tenant.value());var rows=ps.executeQuery()){rows.next();current=rows.getLong(1);}
            if(current!=draft.expectedRevision())throw conflict();
            long revision=current+1;
            try(var ps=tx.statement(c,"INSERT INTO fs.retention_policy_draft VALUES (?,?,?,?,?,?)",
                tenant.value(),revision,draft.retentionSeconds(),draft.quotaMode().name(),actor.actorId(),utc(tx.now()))){ps.executeUpdate();}
            MutationIntents.record(events,audits,clock,correlation,actor,tenant.value(),"file-storage.retention.drafted",
                "file-storage.retention-policy",tenant.value().toString(),Map.of("revision",revision,"state","DRAFT"));
            return revision;
        });
    }
    public void review(ActorReference actor,TenantId tenant,long revision,CorrelationContext correlation){
        require(actor,tenant,null,Action.REVIEW_POLICY);
        tx.execute((c,events,audits)->{
            try(var ps=tx.statement(c,"SELECT author FROM fs.retention_policy_draft WHERE tenant_id=? AND revision=?",tenant.value(),revision);var rows=ps.executeQuery()){
                if(!rows.next())throw missing();if(actor.actorId().equals(rows.getString(1)))throw conflict();
            }
            try(var ps=tx.statement(c,"INSERT INTO fs.retention_policy_review VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
                tenant.value(),revision,actor.actorId(),utc(tx.now()))){if(ps.executeUpdate()!=1)throw conflict();}
            MutationIntents.record(events,audits,clock,correlation,actor,tenant.value(),"file-storage.retention.reviewed",
                "file-storage.retention-policy",tenant.value().toString(),Map.of("revision",revision,"state","REVIEWED_NOT_ACTIVE"));
            return null;
        });
    }
    public UUID assertHold(ActorReference actor,TenantId tenant,FileId file,String evidence,CorrelationContext correlation){
        require(actor,tenant,file,Action.ASSERT_HOLD);LifecycleGovernance.digest(evidence);
        return tx.execute((c,events,audits)->{
            lockFile(c,tenant,file);UUID id=UuidV7.generate();
            try(var ps=tx.statement(c,"INSERT INTO fs.governed_legal_hold VALUES (?,?,?,?,?,?)",
                tenant.value(),file.value(),id,actor.actorId(),evidence,utc(tx.now()))){ps.executeUpdate();}
            MutationIntents.record(events,audits,clock,correlation,actor,tenant.value(),"file-storage.legal-hold.asserted",
                "file-storage.file",file.value(),Map.of("holdId",id.toString()));
            return id;
        });
    }
    public UUID requestReaderReview(ActorReference actor,TenantId tenant,FileId file,String handleHash,String evidence,CorrelationContext correlation){
        require(actor,tenant,file,Action.REQUEST_READER_REVIEW);
        LifecycleGovernance.digest(handleHash);LifecycleGovernance.digest(evidence);
        return tx.execute((c,events,audits)->{
            lockFile(c,tenant,file);
            if(reader(c,tenant,file,handleHash)==null)throw missing();
            UUID id=UuidV7.generate();
            try(var ps=tx.statement(c,"INSERT INTO fs.reader_reconciliation VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",
                id,HexFormat.of().parseHex(handleHash),evidence,actor.actorId(),utc(tx.now()))){if(ps.executeUpdate()!=1)throw conflict();}
            MutationIntents.record(events,audits,clock,correlation,actor,tenant.value(),"file-storage.reader.review-requested",
                "file-storage.file",file.value(),Map.of("requestId",id.toString()));
            return id;
        });
    }
    public void confirmReaderStopped(ActorReference actor,TenantId tenant,FileId file,ReaderReview review,CorrelationContext correlation){
        require(actor,tenant,file,Action.CONFIRM_READER_STOPPED);
        // Trusted proof check outside DB transaction; re-read exact immutable claim before commit.
        ReaderClaim snapshot=tx.execute((c,events,audits)->reviewClaim(c,actor,tenant,file,review));
        boolean confirmed;
        try{confirmed=verifier.confirmedStopped(snapshot,review.evidenceSha256(),actor);}
        catch(RuntimeException ex){throw new Failure(Code.DENIED);}
        if(!confirmed)throw new Failure(Code.DENIED);
        require(actor,tenant,file,Action.CONFIRM_READER_STOPPED);
        tx.execute((c,events,audits)->{
            lockFile(c,tenant,file);
            if(!snapshot.equals(reviewClaim(c,actor,tenant,file,review)))throw conflict();
            try(var ps=tx.statement(c,"INSERT INTO fs.reader_reconciliation_review VALUES (?,?,?,'CONFIRMED_STOPPED') ON CONFLICT DO NOTHING",
                review.requestId(),actor.actorId(),utc(tx.now()))){if(ps.executeUpdate()!=1)throw conflict();}
            MutationIntents.record(events,audits,clock,correlation,actor,tenant.value(),"file-storage.reader.stop-confirmed",
                "file-storage.file",file.value(),Map.of("requestId",review.requestId().toString()));
            return null;
        });
    }
    private ReaderClaim reviewClaim(Connection c,ActorReference actor,TenantId tenant,FileId file,ReaderReview review)throws SQLException{
        try(var ps=tx.statement(c,"""
            SELECT encode(q.handle_hash,'hex'),q.requester,q.evidence_sha256
            FROM fs.reader_reconciliation q JOIN fs.download_transfer_handle h USING(handle_hash)
            WHERE q.request_id=? AND h.tenant_id=? AND h.file_id=?
            """,review.requestId(),tenant.value(),file.value());var rows=ps.executeQuery()){
            if(!rows.next())throw missing();
            if(actor.actorId().equals(rows.getString(2))||!review.evidenceSha256().equals(rows.getString(3)))throw conflict();
            var claim=reader(c,tenant,file,rows.getString(1));if(claim==null)throw missing();return claim;
        }
    }
    private ReaderClaim reader(Connection c,TenantId tenant,FileId file,String hash)throws SQLException{
        try(var ps=tx.statement(c,"""
            SELECT h.version_id,h.claimed_at,h.expires_at FROM fs.download_transfer_handle h
            LEFT JOIN fs.download_reader d USING(handle_hash)
            WHERE h.tenant_id=? AND h.file_id=? AND h.handle_hash=? AND h.claimed_at IS NOT NULL AND d.drained_at IS NULL
            """,tenant.value(),file.value(),HexFormat.of().parseHex(hash));var rows=ps.executeQuery()){
            if(!rows.next())return null;
            return new ReaderClaim(tenant,file,new VersionId(rows.getObject(1,UUID.class)),hash,
                rows.getObject(2,OffsetDateTime.class).toInstant(),rows.getObject(3,OffsetDateTime.class).toInstant());
        }
    }
    private void lockFile(Connection c,TenantId tenant,FileId file)throws SQLException{
        try(var ps=tx.statement(c,"SELECT 1 FROM fs.file WHERE tenant_id=? AND file_id=? FOR UPDATE",tenant.value(),file.value());var rows=ps.executeQuery()){
            if(!rows.next())throw missing();
        }
    }
    private void require(ActorReference actor,TenantId tenant,FileId file,Action action){
        Objects.requireNonNull(actor);Objects.requireNonNull(tenant);
        boolean allowed;
        try{allowed=authority.allows(actor,tenant,file,action);}catch(RuntimeException ex){allowed=false;}
        if(!allowed)throw new Failure(Code.DENIED);
    }
    private static OffsetDateTime utc(Instant value){return value.atOffset(ZoneOffset.UTC);}
    private static Failure missing(){return new Failure(Code.NOT_FOUND);}
    private static Failure conflict(){return new Failure(Code.CONFLICT);}
}
