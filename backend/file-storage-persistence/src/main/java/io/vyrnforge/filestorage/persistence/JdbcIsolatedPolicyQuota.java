package io.vyrnforge.filestorage.persistence;

import io.vyrnforge.filestorage.application.FileApplication.Code;
import io.vyrnforge.filestorage.application.FileApplication.Failure;
import io.vyrnforge.filestorage.application.LifecycleGovernance.*;
import io.vyrnforge.filestorage.domain.RetentionSafety.*;
import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.platform.kernel.context.*;
import io.vyrnforge.platform.kernel.ids.UuidV7;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;

/** Explicit test-tenant-only primitives. Not registered in any runtime; no production activation. */
public final class JdbcIsolatedPolicyQuota {
    private final JdbcUploadTransfers tx;
    private final Clock clock;
    private final TenantId isolatedTenant;
    private final Authority authority;
    public JdbcIsolatedPolicyQuota(DataSource source,Clock clock,TenantId isolatedTenant,Authority authority){
        this.clock=Objects.requireNonNull(clock);this.isolatedTenant=Objects.requireNonNull(isolatedTenant);
        this.authority=Objects.requireNonNull(authority);tx=new JdbcUploadTransfers(source,clock,Duration.ofMinutes(1),5);
    }
    public record Policy(long revision,long retentionSeconds,long quotaLimit,long evidenceTtlSeconds,Instant activatedAt){}
    public enum Status { RESERVED, CHARGED, RELEASED }
    public record Reservation(SessionId session,long policyRevision,long bytes,Status status){}
    public record Usage(BigDecimal reserved,BigDecimal charged,long limit){}
    private record Session(FileId file,VersionId version,LocationId location,String consumer,long bytes,String state,Instant expiresAt,boolean claimed){}
    public Policy activate(ActorReference actor,TenantId tenant,long expected,long revision,long quotaLimit,long evidenceTtlSeconds,CorrelationContext correlation){
        require(actor,tenant,null,Action.ACTIVATE_ISOLATED_POLICY);
        if(expected<0||revision<=expected||quotaLimit<=0||evidenceTtlSeconds<1||evidenceTtlSeconds>86400)throw invalid();
        return tx.execute((c,events,audits)->{
            lockTenant(c,tenant);var current=policy(c,tenant);
            if((current==null?0:current.revision())!=expected)throw conflict();
            long seconds;
            try(var ps=tx.statement(c,"""
                SELECT d.retention_seconds,d.author,r.reviewer,r.reviewed_at FROM fs.retention_policy_draft d
                JOIN fs.retention_policy_review r USING(tenant_id,revision)
                WHERE d.tenant_id=? AND d.revision=? AND d.quota_mode='CHARGE_UNTIL_VERIFIED_PURGE'
                  AND d.revision=(SELECT max(revision) FROM fs.retention_policy_draft WHERE tenant_id=d.tenant_id)
                """,tenant.value(),revision);var rows=ps.executeQuery()){
                if(!rows.next())throw conflict();seconds=rows.getLong(1);
                if(actor.actorId().equals(rows.getString(2))||actor.actorId().equals(rows.getString(3))
                    ||rows.getObject(4,OffsetDateTime.class).toInstant().isAfter(tx.now()))throw conflict();
            }
            if(current!=null&&seconds<current.retentionSeconds())throw conflict();
            if(total(c,tenant).compareTo(BigDecimal.valueOf(quotaLimit))>0)throw conflict();
            var now=tx.now();
            try(var ps=tx.statement(c,"INSERT INTO fs.isolated_policy_activation VALUES (?,?,?,?,?,?)",
                tenant.value(),revision,quotaLimit,evidenceTtlSeconds,utc(now),actor.actorId())){ps.executeUpdate();}
            MutationIntents.record(events,audits,clock,correlation,actor,tenant.value(),"file-storage.retention.isolated-activated",
                "file-storage.retention-policy",tenant.value().toString(),Map.of("revision",revision,"environment","ISOLATED"));
            return new Policy(revision,seconds,quotaLimit,evidenceTtlSeconds,now);
        });
    }
    public Policy resolve(ActorReference actor,TenantId tenant,long expected){
        require(actor,tenant,null,Action.RESOLVE_ISOLATED_POLICY);
        return tx.execute((c,events,audits)->{lockTenant(c,tenant);return current(c,tenant,expected);});
    }
    /** Generate conservative evidence. No source may infer CLEAR from absence of a local hold.
     * This isolated foundation only emits UNKNOWN or protective HELD, never a purge-ready fact.
     */
    public Evidence observe(ActorReference actor,TenantId tenant,FileId file,long generation,long expectedPolicy,CorrelationContext correlation){
        require(actor,tenant,file,Action.GENERATE_ISOLATED_EVIDENCE);
        if(generation<=0||generation%2!=1)throw invalid();
        return tx.execute((c,events,audits)->{
            Instant deleted;
            try(var ps=tx.statement(c,"SELECT deleted_at FROM fs.file WHERE tenant_id=? AND file_id=? AND state='SOFT_DELETED' AND lifecycle_revision=? FOR UPDATE",
                tenant.value(),file.value(),generation);var rows=ps.executeQuery()){
                if(!rows.next())throw conflict();deleted=rows.getObject(1,OffsetDateTime.class).toInstant();
            }
            lockTenant(c,tenant);var policy=current(c,tenant,expectedPolicy);var now=tx.now();
            if(deleted.isAfter(now))throw conflict();
            Instant retain;
            try{retain=deleted.plusSeconds(policy.retentionSeconds());}catch(DateTimeException|ArithmeticException ex){throw invalid();}
            Hold hold=Hold.UNKNOWN;
            try(var ps=tx.statement(c,"SELECT 1 FROM fs.governed_legal_hold WHERE tenant_id=? AND file_id=? LIMIT 1",
                tenant.value(),file.value());var rows=ps.executeQuery()){if(rows.next())hold=Hold.HELD;}
            // Never shorten previously observed retention, including earlier generations.
            try(var ps=tx.statement(c,"SELECT max(retain_until) FROM fs.file_retention_evidence WHERE tenant_id=? AND file_id=?",
                tenant.value(),file.value());var rows=ps.executeQuery()){
                rows.next();var previous=rows.getObject(1,OffsetDateTime.class);if(previous!=null&&previous.toInstant().isAfter(retain))retain=previous.toInstant();
            }
            var until=now.plusSeconds(policy.evidenceTtlSeconds());
            try(var ps=tx.statement(c,"""
                INSERT INTO fs.isolated_retention_observation VALUES (?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(tenant_id,file_id,lifecycle_revision,observed_at) DO NOTHING
                """,UuidV7.generate(),tenant.value(),file.value(),generation,policy.revision(),utc(retain),hold.name(),utc(now),utc(until),actor.actorId())){
                if(ps.executeUpdate()!=1)throw conflict();
            }
            try(var ps=tx.statement(c,"""
                INSERT INTO fs.file_retention_evidence(tenant_id,file_id,lifecycle_revision,policy_revision,hold_state,retain_until,observed_at,valid_until)
                VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(tenant_id,file_id,lifecycle_revision) DO UPDATE SET
                policy_revision=EXCLUDED.policy_revision,hold_state=EXCLUDED.hold_state,retain_until=EXCLUDED.retain_until,
                observed_at=EXCLUDED.observed_at,valid_until=EXCLUDED.valid_until
                WHERE fs.file_retention_evidence.observed_at<EXCLUDED.observed_at
                """,tenant.value(),file.value(),generation,policy.revision(),hold.name(),utc(retain),utc(now),utc(until))){
                if(ps.executeUpdate()!=1)throw conflict();
            }
            MutationIntents.record(events,audits,clock,correlation,actor,tenant.value(),"file-storage.retention.isolated-observed",
                "file-storage.file",file.value(),Map.of("lifecycleRevision",generation,"policyRevision",policy.revision(),"holdState",hold.name()));
            return new Evidence(generation,policy.revision(),hold,retain,now,until);
        });
    }
    public Reservation reserve(ActorReference actor,TenantId tenant,SessionId session,long expectedPolicy,CorrelationContext correlation){
        require(actor,tenant,null,Action.ACCOUNT_ISOLATED_QUOTA);
        return tx.execute((c,events,audits)->{
            lockTenant(c,tenant);var existing=reservation(c,tenant,session);
            if(existing!=null){if(existing.policyRevision()!=expectedPolicy)throw conflict();return existing;}
            var policy=current(c,tenant,expectedPolicy);var s=session(c,tenant,session);
            if(!"INITIATED".equals(s.state())||s.claimed()||!s.expiresAt().isAfter(tx.now()))throw conflict();
            if(total(c,tenant).add(BigDecimal.valueOf(s.bytes())).compareTo(BigDecimal.valueOf(policy.quotaLimit()))>0)throw conflict();
            try(var ps=tx.statement(c,"INSERT INTO fs.isolated_quota_reservation VALUES (?,?,?,?,?,?,?,?,'RESERVED',?,NULL)",
                session.value(),tenant.value(),s.file().value(),s.version().value(),s.location().value(),s.consumer(),policy.revision(),s.bytes(),utc(tx.now()))){ps.executeUpdate();}
            MutationIntents.record(events,audits,clock,correlation,actor,tenant.value(),"file-storage.quota.isolated-reserved",
                "file-storage.file",s.file().value(),Map.of("sessionId",session.value().toString(),"bytes",s.bytes(),"policyRevision",policy.revision()));
            return new Reservation(session,policy.revision(),s.bytes(),Status.RESERVED);
        });
    }
    public Reservation settle(ActorReference actor,TenantId tenant,SessionId session,Status target,CorrelationContext correlation){
        require(actor,tenant,null,Action.ACCOUNT_ISOLATED_QUOTA);
        if(target==null||target==Status.RESERVED)throw invalid();
        return tx.execute((c,events,audits)->{
            lockTenant(c,tenant);var existing=reservation(c,tenant,session);
            if(existing==null)throw missing();
            if(existing.status()==target)return existing;
            if(existing.status()!=Status.RESERVED)throw conflict();
            var s=session(c,tenant,session);
            if(target==Status.CHARGED){
                if(!"COMPLETED".equals(s.state()))throw conflict();
            }else if(!Set.of("FAILED","EXPIRED").contains(s.state())||s.claimed())throw conflict();
            try(var ps=tx.statement(c,"UPDATE fs.isolated_quota_reservation SET status=?,finished_at=? WHERE tenant_id=? AND session_id=? AND status='RESERVED'",
                target.name(),utc(tx.now()),tenant.value(),session.value())){if(ps.executeUpdate()!=1)throw conflict();}
            MutationIntents.record(events,audits,clock,correlation,actor,tenant.value(),"file-storage.quota.isolated-settled",
                "file-storage.file",s.file().value(),Map.of("sessionId",session.value().toString(),"bytes",existing.bytes(),"status",target.name()));
            return new Reservation(session,existing.policyRevision(),existing.bytes(),target);
        });
    }
    public Usage usage(ActorReference actor,TenantId tenant){
        require(actor,tenant,null,Action.ACCOUNT_ISOLATED_QUOTA);
        return tx.execute((c,events,audits)->{
            lockTenant(c,tenant);var p=policy(c,tenant);if(p==null)throw missing();
            try(var ps=tx.statement(c,"""
                SELECT COALESCE(sum(bytes) FILTER(WHERE status='RESERVED'),0),COALESCE(sum(bytes) FILTER(WHERE status='CHARGED'),0)
                FROM fs.isolated_quota_reservation WHERE tenant_id=?
                """,tenant.value());var rows=ps.executeQuery()){rows.next();return new Usage(rows.getBigDecimal(1),rows.getBigDecimal(2),p.quotaLimit());}
        });
    }
    private Session session(Connection c,TenantId tenant,SessionId session)throws SQLException{
        try(var ps=tx.statement(c,"""
            SELECT s.file_id,s.version_id,s.location_id,s.consumer,r.expected_size,s.state,s.expires_at
            FROM fs.upload_session s JOIN fs.initiation_request r ON r.session_id=s.session_id AND r.tenant_id=s.tenant_id
            WHERE s.tenant_id=? AND s.session_id=? FOR UPDATE OF s
            """,tenant.value(),session.value());var rows=ps.executeQuery()){
            if(!rows.next())throw missing();
            // Read claim status in a fresh statement after acquiring the session lock.
            boolean claimed;
            try(var p=tx.statement(c,"SELECT 1 FROM fs.upload_transfer_handle WHERE session_id=? AND claimed_at IS NOT NULL LIMIT 1",
                session.value());var claims=p.executeQuery()){claimed=claims.next();}
            return new Session(new FileId(rows.getString(1)),new VersionId(rows.getObject(2,UUID.class)),new LocationId(rows.getObject(3,UUID.class)),
                rows.getString(4),rows.getLong(5),rows.getString(6),rows.getObject(7,OffsetDateTime.class).toInstant(),claimed);
        }
    }
    private Reservation reservation(Connection c,TenantId tenant,SessionId session)throws SQLException{
        try(var ps=tx.statement(c,"SELECT policy_revision,bytes,status FROM fs.isolated_quota_reservation WHERE tenant_id=? AND session_id=?",
            tenant.value(),session.value());var rows=ps.executeQuery()){
            return rows.next()?new Reservation(session,rows.getLong(1),rows.getLong(2),Status.valueOf(rows.getString(3))):null;
        }
    }
    private BigDecimal total(Connection c,TenantId tenant)throws SQLException{
        try(var ps=tx.statement(c,"SELECT COALESCE(sum(bytes),0) FROM fs.isolated_quota_reservation WHERE tenant_id=? AND status<>'RELEASED'",
            tenant.value());var rows=ps.executeQuery()){rows.next();return rows.getBigDecimal(1);}
    }
    private Policy current(Connection c,TenantId tenant,long expected)throws SQLException{
        var p=policy(c,tenant);if(p==null||p.revision()!=expected||p.activatedAt().isAfter(tx.now()))throw conflict();return p;
    }
    private Policy policy(Connection c,TenantId tenant)throws SQLException{
        try(var ps=tx.statement(c,"""
            SELECT a.revision,d.retention_seconds,a.quota_limit,a.evidence_ttl_seconds,a.activated_at
            FROM fs.isolated_policy_activation a JOIN fs.retention_policy_draft d USING(tenant_id,revision)
            WHERE a.tenant_id=? ORDER BY a.revision DESC LIMIT 1
            """,tenant.value());var rows=ps.executeQuery()){
            return rows.next()?new Policy(rows.getLong(1),rows.getLong(2),rows.getLong(3),rows.getLong(4),rows.getObject(5,OffsetDateTime.class).toInstant()):null;
        }
    }
    static void lockTenant(Connection c,TenantId tenant)throws SQLException{
        try(var ps=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text),3105))")){
            ps.setQueryTimeout(5);ps.setString(1,tenant.value().toString());try(var rows=ps.executeQuery()){rows.next();}
        }
    }
    private void require(ActorReference actor,TenantId tenant,FileId file,Action action){
        Objects.requireNonNull(actor);Objects.requireNonNull(tenant);
        boolean allowed=false;
        try{allowed=isolatedTenant.equals(tenant)&&authority.allows(actor,tenant,file,action);}catch(RuntimeException ignored){}
        if(!allowed)throw new Failure(Code.DENIED);
    }
    private static OffsetDateTime utc(Instant i){return i.atOffset(ZoneOffset.UTC);}
    private static Failure conflict(){return new Failure(Code.CONFLICT);}
    private static Failure missing(){return new Failure(Code.NOT_FOUND);}
    private static Failure invalid(){return new Failure(Code.INVALID);}
}
