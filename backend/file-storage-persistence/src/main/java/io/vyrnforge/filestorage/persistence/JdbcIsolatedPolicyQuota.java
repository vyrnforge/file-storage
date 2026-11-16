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
            if(current!=null&&(seconds<current.retentionSeconds()||current.activatedAt().isAfter(tx.now())))throw conflict();
            if(total(c,tenant).compareTo(BigDecimal.valueOf(quotaLimit))>0)throw conflict();
            var now=tx.now();
            try(var ps=tx.statement(c,"INSERT INTO fs.isolated_policy_activation VALUES (?,?,?,?,?,?)",
                tenant.value(),revision,quotaLimit,evidenceTtlSeconds,utc(now),actor.actorId())){ps.executeUpdate();}
            MutationIntents.record(events,audits,clock,correlation,actor,tenant.value(),"file-storage.retention.isolated-activated",
                "file-storage.retention-policy",tenant.value().toString(),Map.of("revision",revision,"environment","ISOLATED"));
            return new Policy(revision,seconds,quotaLimit,evidenceTtlSeconds,now);
        });
    }
    /** Exact quota values are independently reviewed against a retention revision. */
    public record QuotaDraft(long expectedActive,long revision,long limit,long evidenceTtlSeconds){
        public QuotaDraft{
            if(expectedActive<0||revision<=expectedActive||limit<=0||evidenceTtlSeconds<1||evidenceTtlSeconds>86400)
                throw new Failure(Code.INVALID);
        }
    }
    public void draftQuota(ActorReference actor,TenantId tenant,QuotaDraft draft,CorrelationContext correlation){
        require(actor,tenant,null,Action.DRAFT_ISOLATED_QUOTA);Objects.requireNonNull(draft);
        tx.execute((c,events,audits)->{
            lockTenant(c,tenant);
            try(var ps=tx.statement(c,"INSERT INTO fs.isolated_quota_policy_draft VALUES (?,?,?,?,?,?,?)",
                tenant.value(),draft.revision(),draft.expectedActive(),draft.limit(),draft.evidenceTtlSeconds(),actor.actorId(),utc(tx.now()))){
                ps.executeUpdate();
            }
            MutationIntents.record(events,audits,clock,correlation,actor,tenant.value(),"file-storage.quota.isolated-drafted",
                "file-storage.retention-policy",tenant.value().toString(),Map.of("revision",draft.revision(),"limit",draft.limit(),"evidenceTtlSeconds",draft.evidenceTtlSeconds()));
            return null;
        });
    }
    public void reviewQuota(ActorReference actor,TenantId tenant,long revision,CorrelationContext correlation){
        require(actor,tenant,null,Action.REVIEW_ISOLATED_QUOTA);
        tx.execute((c,events,audits)->{
            lockTenant(c,tenant);
            try(var ps=tx.statement(c,"INSERT INTO fs.isolated_quota_policy_review VALUES (?,?,?,?)",
                tenant.value(),revision,actor.actorId(),utc(tx.now()))){ps.executeUpdate();}
            MutationIntents.record(events,audits,clock,correlation,actor,tenant.value(),"file-storage.quota.isolated-reviewed",
                "file-storage.retention-policy",tenant.value().toString(),Map.of("revision",revision,"state","REVIEWED_NOT_ACTIVE"));
            return null;
        });
    }
    /** Immutable reviewed values are loaded, then activation CAS and the database guard recheck
     * the exact revision in the write transaction. No caller-supplied limit or TTL. */
    public Policy activateReviewed(ActorReference actor,TenantId tenant,long expected,long revision,CorrelationContext correlation){
        require(actor,tenant,null,Action.ACTIVATE_ISOLATED_POLICY);
        QuotaDraft draft=tx.execute((c,events,audits)->{
            try(var ps=tx.statement(c,"""
                SELECT q.quota_limit,q.evidence_ttl_seconds
                FROM fs.isolated_quota_policy_draft q JOIN fs.isolated_quota_policy_review r USING(tenant_id,revision)
                WHERE q.tenant_id=? AND q.revision=? AND q.expected_active=?
                """,tenant.value(),revision,expected);var rows=ps.executeQuery()){
                if(!rows.next())throw conflict();
                return new QuotaDraft(expected,revision,rows.getLong(1),rows.getLong(2));
            }
        });
        return activate(actor,tenant,expected,revision,draft.limit(),draft.evidenceTtlSeconds(),correlation);
    }

    public enum FindingDisposition { ACKNOWLEDGED, NEEDS_INVESTIGATION }
    public record Finding(UUID id,Long policyRevision,boolean integrated,Instant capturedAt,
                          long missingReservations,long mismatchedReservations,long untrackedVersions,long uncertainTerminal,
                          BigDecimal candidateReservedBytes,BigDecimal candidateChargedBytes,
                          BigDecimal candidateReleasedBytes,long unresolvedSessions){}
    /** Single database snapshot of metadata and ledger; only the finding and audit/outbox are written.
     * Candidate bytes are estimates, never permission to apply historical accounting or remove objects. */
    public Finding captureFinding(ActorReference actor,TenantId tenant,CorrelationContext correlation){
        require(actor,tenant,null,Action.CAPTURE_QUOTA_FINDING);
        return tx.execute((c,events,audits)->{
            UUID id=UuidV7.generate();var now=tx.now();
            Finding finding;
            try(var ps=tx.statement(c,"""
                WITH sessions AS (
                  SELECT s.*,r.expected_size,q.session_id AS reserved_id,q.bytes,q.status,
                    q.file_id AS qfile,q.version_id AS qversion,q.location_id AS qlocation,q.consumer AS qconsumer,
                    v.state AS version_state,e.verdict,e.observed_size,
                    EXISTS(SELECT 1 FROM fs.upload_terminal_recovery x WHERE x.session_id=s.session_id) AS recovered,
                    EXISTS(SELECT 1 FROM fs.upload_transfer_handle h WHERE h.session_id=s.session_id AND h.claimed_at IS NOT NULL) AS claimed
                  FROM fs.upload_session s
                  LEFT JOIN fs.initiation_request r ON r.session_id=s.session_id AND r.tenant_id=s.tenant_id
                  LEFT JOIN fs.isolated_quota_reservation q ON q.session_id=s.session_id AND q.tenant_id=s.tenant_id
                  LEFT JOIN fs.file_version v ON v.version_id=s.version_id AND v.tenant_id=s.tenant_id AND v.file_id=s.file_id AND v.location_id=s.location_id
                  LEFT JOIN fs.upload_scan_evidence e ON e.session_id=s.session_id
                  WHERE s.tenant_id=?
                ), classified AS (
                  SELECT *,CASE
                    WHEN expected_size IS NULL THEN 'UNRESOLVED'
                    WHEN state='COMPLETED' AND version_state='AVAILABLE' AND verdict='CLEAN' AND observed_size=expected_size THEN 'CHARGED'
                    WHEN state='INITIATED' AND version_state='PENDING' THEN 'RESERVED'
                    WHEN state='VERIFYING' AND version_state='VERIFYING' THEN 'RESERVED'
                    WHEN state IN ('FAILED','EXPIRED') AND version_state='FAILED' AND NOT claimed AND recovered THEN 'RELEASED'
                    ELSE 'UNRESOLVED' END AS candidate
                  FROM sessions
                )
                INSERT INTO fs.isolated_quota_finding
                (finding_id,tenant_id,policy_revision,integrated,captured_at,requester,missing_reservations,mismatched_reservations,
                 untracked_versions,uncertain_terminal,candidate_reserved_bytes,candidate_charged_bytes,candidate_released_bytes,unresolved_sessions)
                SELECT ?,?,(SELECT max(revision) FROM fs.isolated_policy_activation WHERE tenant_id=?),
                  EXISTS(SELECT 1 FROM fs.isolated_quota_fence WHERE tenant_id=? AND enabled_revision IS NOT NULL),?,?,
                  count(*) FILTER(WHERE reserved_id IS NULL),
                  count(*) FILTER(WHERE reserved_id IS NOT NULL AND
                    (ROW(file_id,version_id,location_id,consumer,expected_size) IS DISTINCT FROM ROW(qfile,qversion,qlocation,qconsumer,bytes)
                     OR status IS DISTINCT FROM CASE WHEN state='COMPLETED' THEN 'CHARGED'
                          WHEN state IN ('FAILED','EXPIRED') AND NOT claimed THEN 'RELEASED' ELSE 'RESERVED' END
                     OR (status='CHARGED' AND candidate<>'CHARGED'))),
                  (SELECT count(*) FROM fs.file_version v WHERE v.tenant_id=? AND NOT EXISTS
                    (SELECT 1 FROM fs.upload_session s WHERE s.tenant_id=v.tenant_id AND s.version_id=v.version_id)),
                  count(*) FILTER(WHERE state IN ('FAILED','EXPIRED') AND claimed AND (status='RESERVED' OR reserved_id IS NULL)),
                  COALESCE(sum(expected_size) FILTER(WHERE reserved_id IS NULL AND candidate='RESERVED'),0),
                  COALESCE(sum(expected_size) FILTER(WHERE reserved_id IS NULL AND candidate='CHARGED'),0),
                  COALESCE(sum(expected_size) FILTER(WHERE reserved_id IS NULL AND candidate='RELEASED'),0),
                  count(*) FILTER(WHERE reserved_id IS NULL AND candidate='UNRESOLVED')
                FROM classified RETURNING *
                """,tenant.value(),id,tenant.value(),tenant.value(),tenant.value(),utc(now),actor.actorId(),tenant.value());var rows=ps.executeQuery()){
                rows.next();finding=readFinding(rows);
            }
            MutationIntents.record(events,audits,clock,correlation,actor,tenant.value(),"file-storage.quota.finding-captured",
                "file-storage.quota-finding",id.toString(),Map.of("missing",finding.missingReservations(),"mismatched",finding.mismatchedReservations(),"mode","DRY_RUN_ONLY"));
            return finding;
        });
    }
    public Finding finding(ActorReference actor,TenantId tenant,UUID id){
        require(actor,tenant,null,Action.RECONCILE_ISOLATED_QUOTA);
        return tx.execute((c,events,audits)->{
            try(var ps=tx.statement(c,"SELECT * FROM fs.isolated_quota_finding WHERE tenant_id=? AND finding_id=?",
                tenant.value(),id);var rows=ps.executeQuery()){if(!rows.next())throw missing();return readFinding(rows);}
        });
    }
    public void reviewFinding(ActorReference actor,TenantId tenant,UUID id,FindingDisposition disposition,CorrelationContext correlation){
        require(actor,tenant,null,Action.REVIEW_QUOTA_FINDING);Objects.requireNonNull(disposition);
        tx.execute((c,events,audits)->{
            try(var ps=tx.statement(c,"INSERT INTO fs.isolated_quota_finding_review VALUES (?,?,?,?,?)",
                tenant.value(),id,actor.actorId(),utc(tx.now()),disposition.name())){ps.executeUpdate();}
            MutationIntents.record(events,audits,clock,correlation,actor,tenant.value(),"file-storage.quota.finding-reviewed",
                "file-storage.quota-finding",id.toString(),Map.of("disposition",disposition.name(),"mode","DRY_RUN_ONLY"));
            return null;
        });
    }
    private static Finding readFinding(ResultSet rows)throws SQLException{
        return new Finding(rows.getObject("finding_id",UUID.class),rows.getObject("policy_revision",Long.class),
            rows.getBoolean("integrated"),rows.getObject("captured_at",OffsetDateTime.class).toInstant(),
            rows.getLong("missing_reservations"),rows.getLong("mismatched_reservations"),rows.getLong("untracked_versions"),
            rows.getLong("uncertain_terminal"),rows.getBigDecimal("candidate_reserved_bytes"),rows.getBigDecimal("candidate_charged_bytes"),
            rows.getBigDecimal("candidate_released_bytes"),rows.getLong("unresolved_sessions"));
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
            try(var ps=tx.statement(c,"SELECT max(retain_until) FROM (SELECT retain_until FROM fs.file_retention_evidence WHERE tenant_id=? AND file_id=? UNION ALL SELECT retain_until FROM fs.isolated_retention_observation WHERE tenant_id=? AND file_id=?) deadlines",
                tenant.value(),file.value(),tenant.value(),file.value());var rows=ps.executeQuery()){
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
    /** Irreversible opt-in for a fresh, explicitly isolated tenant. No backfill. */
    public void enableIntegration(ActorReference actor,TenantId tenant,long expected,CorrelationContext correlation){
        require(actor,tenant,null,Action.ENABLE_ISOLATED_QUOTA_INTEGRATION);
        tx.execute((c,events,audits)->{
            lockTenant(c,tenant);current(c,tenant,expected);
            if(integrated(c,tenant))throw conflict();
            try(var ps=tx.statement(c,"SELECT fs.touch_quota_fence(?)",tenant.value());var rows=ps.executeQuery()){rows.next();}
            try(var ps=tx.statement(c,"SELECT 1 FROM fs.file WHERE tenant_id=? LIMIT 1",tenant.value());var rows=ps.executeQuery()){
                if(rows.next())throw conflict();
            }
            try(var ps=tx.statement(c,"UPDATE fs.isolated_quota_fence SET enabled_revision=? WHERE tenant_id=? AND enabled_revision IS NULL",expected,tenant.value())){
                if(ps.executeUpdate()!=1)throw conflict();
            }
            MutationIntents.record(events,audits,clock,correlation,actor,tenant.value(),"file-storage.quota.isolated-integration-enabled",
                "file-storage.retention-policy",tenant.value().toString(),Map.of("revision",expected,"environment","ISOLATED"));
            return null;
        });
    }

    /** One snapshot, counts only; never repairs or backfills historical data. */
    public record Reconciliation(boolean integrated,long missingReservations,long mismatchedReservations,
                                 long untrackedVersions,long uncertainTerminalReservations){}
    public Reconciliation reconcile(ActorReference actor,TenantId tenant){
        require(actor,tenant,null,Action.RECONCILE_ISOLATED_QUOTA);
        return tx.execute((c,events,audits)->{
            try(var ps=tx.statement(c,"""
                WITH sessions AS (
                  SELECT s.*,r.expected_size,q.session_id AS reserved_id,q.bytes,q.status,
                    q.file_id AS qfile,q.version_id AS qversion,q.location_id AS qlocation,q.consumer AS qconsumer,
                    EXISTS(SELECT 1 FROM fs.upload_transfer_handle h WHERE h.session_id=s.session_id AND h.claimed_at IS NOT NULL) AS claimed
                  FROM fs.upload_session s
                  LEFT JOIN fs.initiation_request r ON r.session_id=s.session_id AND r.tenant_id=s.tenant_id
                  LEFT JOIN fs.isolated_quota_reservation q ON q.session_id=s.session_id AND q.tenant_id=s.tenant_id
                  WHERE s.tenant_id=?
                )
                SELECT EXISTS(SELECT 1 FROM fs.isolated_quota_fence WHERE tenant_id=? AND enabled_revision IS NOT NULL),
                  count(*) FILTER(WHERE reserved_id IS NULL),
                  count(*) FILTER(WHERE reserved_id IS NOT NULL AND
                    (ROW(file_id,version_id,location_id,consumer,expected_size) IS DISTINCT FROM ROW(qfile,qversion,qlocation,qconsumer,bytes)
                     OR status IS DISTINCT FROM CASE WHEN state='COMPLETED' THEN 'CHARGED'
                          WHEN state IN ('FAILED','EXPIRED') AND NOT claimed THEN 'RELEASED' ELSE 'RESERVED' END)),
                  (SELECT count(*) FROM fs.file_version v WHERE v.tenant_id=? AND NOT EXISTS
                    (SELECT 1 FROM fs.upload_session s WHERE s.tenant_id=v.tenant_id AND s.version_id=v.version_id)),
                  count(*) FILTER(WHERE state IN ('FAILED','EXPIRED') AND claimed AND status='RESERVED')
                FROM sessions
                """,tenant.value(),tenant.value(),tenant.value());var rows=ps.executeQuery()){
                rows.next();return new Reconciliation(rows.getBoolean(1),rows.getLong(2),rows.getLong(3),rows.getLong(4),rows.getLong(5));
            }
        });
    }
    private boolean integrated(Connection c,TenantId tenant)throws SQLException{
        try(var ps=tx.statement(c,"SELECT 1 FROM fs.isolated_quota_fence WHERE tenant_id=? AND enabled_revision IS NOT NULL",tenant.value());var rows=ps.executeQuery()){
            return rows.next();
        }
    }
    public Reservation reserve(ActorReference actor,TenantId tenant,SessionId session,long expectedPolicy,CorrelationContext correlation){
        require(actor,tenant,null,Action.ACCOUNT_ISOLATED_QUOTA);
        return tx.execute((c,events,audits)->{
            lockTenant(c,tenant);if(integrated(c,tenant))throw conflict();var existing=reservation(c,tenant,session);
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
            lockTenant(c,tenant);if(integrated(c,tenant))throw conflict();var existing=reservation(c,tenant,session);
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
