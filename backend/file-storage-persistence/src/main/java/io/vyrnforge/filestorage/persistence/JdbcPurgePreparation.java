package io.vyrnforge.filestorage.persistence;

import io.vyrnforge.filestorage.application.FileApplication.*;
import io.vyrnforge.filestorage.domain.RetentionSafety;
import io.vyrnforge.filestorage.domain.RetentionSafety.*;
import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.platform.kernel.context.*;
import io.vyrnforge.platform.kernel.ids.UuidV7;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;

/** Immutable review request. Never enqueues work, resolves credentials or calls a provider. */
final class JdbcPurgePreparation {
    private final JdbcUploadTransfers tx;
    private final Clock clock;
    JdbcPurgePreparation(DataSource source,Clock clock){
        this.clock=Objects.requireNonNull(clock);
        tx=new JdbcUploadTransfers(source,clock,Duration.ofMinutes(1),5);
    }

    private record Target(File file,long revision,VersionId version,LocationId location,BackendId backend,
                          long configRevision,String keyHash,String digest,long size,String purpose,
                          String ownerActor,String ownerConsumer) {}

    PurgePreparation prepare(Caller caller,PurgeRequest request,List<CatalogGrant> grants,CorrelationContext correlation){
        if(grants.isEmpty()||grants.size()>256)throw missing();
        return tx.execute((c,events,audits)->{
            // Same file lock as restore/delete and the inverse of download's shared file lock.
            File file;long revision;
            try(var ps=tx.statement(c,"""
                SELECT name,state,lifecycle_revision FROM fs.file WHERE tenant_id=? AND file_id=? FOR UPDATE
                """,caller.tenant().value(),request.file().value());var rows=ps.executeQuery()){
                if(!rows.next())throw missing();
                file=new File(request.file(),caller.tenant(),rows.getString(1),FileState.valueOf(rows.getString(2)));
                revision=rows.getLong(3);
            }
            var target=target(c,caller,file,revision);
            lockGrant(c,caller,target,grants);
            if(file.state()!=FileState.SOFT_DELETED||revision!=request.lifecycleRevision()
                    ||!target.version().equals(request.version()))throw conflict();
            var evidence=evidence(c,caller,request);
            var now=tx.now();
            if(evidence==null||evidence.policyRevision()!=request.policyRevision()
                    ||RetentionSafety.assess(file.state(),revision,evidence,now)!=Assessment.REVIEW_REQUIRED)
                throw conflict();
            boolean priorClaim=priorClaim(c,caller,request);
            // One stable operation per deletion generation. Replays reauthorize and revalidate
            // current policy evidence and exact target; different requesters cannot adopt it.
            try(var ps=tx.statement(c,"""
                SELECT operation_id,version_id,location_id,backend_id,config_revision,object_key_sha256,
                       content_sha256,content_size,policy_revision,retain_until,evidence_observed_at,
                       evidence_valid_until,actor,consumer,prior_download_claim
                FROM fs.purge_preparation WHERE tenant_id=? AND file_id=? AND lifecycle_revision=?
                """,caller.tenant().value(),request.file().value(),revision);var rows=ps.executeQuery()){
                if(rows.next()){
                    if(!target.version().value().equals(rows.getObject(2,UUID.class))
                        ||!target.location().value().equals(rows.getObject(3,UUID.class))
                        ||!target.backend().value().equals(rows.getObject(4,UUID.class))
                        ||target.configRevision()!=rows.getLong(5)||!target.keyHash().equals(rows.getString(6))
                        ||!target.digest().equals(rows.getString(7))||target.size()!=rows.getLong(8)
                        ||evidence.policyRevision()!=rows.getLong(9)||!evidence.retainUntil().equals(instant(rows,10))
                        ||!evidence.observedAt().equals(instant(rows,11))||!evidence.validUntil().equals(instant(rows,12))
                        ||!caller.actor().equals(rows.getString(13))||!caller.consumer().equals(rows.getString(14))
                        ||priorClaim!=rows.getBoolean(15))throw conflict();
                    return result(rows.getObject(1,UUID.class),target,evidence);
                }
            }
            UUID operation=UuidV7.generate();
            try(var ps=tx.statement(c,"""
                INSERT INTO fs.purge_preparation(operation_id,tenant_id,file_id,version_id,location_id,backend_id,
                    config_revision,object_key_sha256,content_sha256,content_size,lifecycle_revision,policy_revision,
                    retain_until,evidence_observed_at,evidence_valid_until,actor,consumer,correlation_id,created_at,prior_download_claim)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,operation,caller.tenant().value(),file.id().value(),target.version().value(),target.location().value(),
                target.backend().value(),target.configRevision(),target.keyHash(),target.digest(),target.size(),revision,
                evidence.policyRevision(),utc(evidence.retainUntil()),utc(evidence.observedAt()),utc(evidence.validUntil()),
                caller.actor(),caller.consumer(),correlation.correlationId(),utc(now),priorClaim)){
                if(ps.executeUpdate()!=1)throw conflict();
            }
            MutationIntents.record(events,audits,clock,correlation,new ActorReference(ActorType.USER,caller.actor(),null,null),
                caller.tenant().value(),"file-storage.file.purge-prepared","file-storage.file",file.id().value(),
                Map.of("operationId",operation.toString(),"versionId",target.version().value().toString(),
                    "lifecycleRevision",revision,"policyRevision",evidence.policyRevision(),"disposition","REVIEW_REQUIRED"));
            return result(operation,target,evidence);
        });
    }

    private Target target(Connection c,Caller caller,File file,long revision)throws SQLException{
        // Do not accept a caller-supplied location or resolve a provider. Use the exact original
        // clean pin, including measured integrity and successful scan operation.
        try(var ps=tx.statement(c,"""
            SELECT v.version_id,v.location_id,s.backend_id,s.config_revision,v.object_key,e.observed_sha256,
                   e.observed_size,s.purpose,r.actor,s.consumer
            FROM fs.file_version v JOIN fs.upload_session s ON s.tenant_id=v.tenant_id AND s.file_id=v.file_id
                AND s.version_id=v.version_id AND s.location_id=v.location_id
            JOIN fs.initiation_request r ON r.tenant_id=s.tenant_id AND r.session_id=s.session_id AND r.consumer=s.consumer
            JOIN fs.upload_transfer_handle h ON h.session_id=s.session_id AND h.tenant_id=s.tenant_id
                AND h.actor=r.actor AND h.consumer=s.consumer AND h.file_id=s.file_id AND h.version_id=s.version_id
                AND h.backend_id=s.backend_id AND h.config_revision=s.config_revision AND h.location_id=s.location_id
                AND h.object_key=v.object_key AND h.expected_size=r.expected_size AND h.claimed_at IS NOT NULL
            JOIN fs.upload_integrity_verification i ON i.session_id=s.session_id
            JOIN fs.upload_scan_evidence e ON e.session_id=s.session_id AND e.observed_size=i.observed_size
                AND e.observed_sha256=i.observed_sha256
            JOIN fs.storage_operation o ON o.operation_id=e.operation_id AND o.tenant_id=s.tenant_id
                AND o.file_id=s.file_id AND o.version_id=s.version_id AND o.location_id=s.location_id
                AND o.operation_key=s.session_id::text AND o.kind='SCAN_UPLOAD' AND o.status='SUCCEEDED'
            WHERE v.tenant_id=? AND v.file_id=? AND v.state='AVAILABLE' AND s.state='COMPLETED'
                AND e.verdict='CLEAN' AND i.verified_at IS NOT NULL AND e.observed_size=h.expected_size
                AND e.detected_mime_type=r.declared_mime_type
            FOR SHARE OF v,s,r,h,i,e,o
            """,caller.tenant().value(),file.id().value());var rows=ps.executeQuery()){
            if(!rows.next())throw missing();
            var target=new Target(file,revision,new VersionId(rows.getObject(1,UUID.class)),
                new LocationId(rows.getObject(2,UUID.class)),new BackendId(rows.getObject(3,UUID.class)),
                rows.getLong(4),hash(rows.getString(5)),rows.getString(6),rows.getLong(7),
                rows.getString(8),rows.getString(9),rows.getString(10));
            if(rows.next())throw conflict();
            return target;
        }
    }

    private void lockGrant(Connection c,Caller caller,Target target,List<CatalogGrant> grants)throws SQLException{
        var now=tx.now();var id=target.file().id();
        boolean owner=caller.actor().equals(target.ownerActor())&&caller.consumer().equals(target.ownerConsumer());
        if(grants.stream().noneMatch(g->g.purpose().equals(target.purpose())&&g.expiresAt().isAfter(now)
                &&(id.equals(g.file())||(g.file()==null&&owner))))throw missing();
        try(var ps=tx.statement(c,"""
            SELECT expires_at FROM fs.file_grant WHERE tenant_id=? AND actor=? AND consumer=? AND purpose=?
              AND action='PREPARE_PURGE' AND NOT revoked AND not_before<=? AND expires_at>?
              AND ((scope='OWNED_FILE' AND ?) OR (scope='EXACT_FILE' AND file_id=?))
            ORDER BY grant_id LIMIT 1 FOR SHARE
            """,caller.tenant().value(),caller.actor(),caller.consumer(),target.purpose(),utc(now),utc(now),owner,id.value());
            var rows=ps.executeQuery()){
            if(!rows.next()||!clock.instant().isBefore(instant(rows,1)))throw missing();
        }
    }

    private Evidence evidence(Connection c,Caller caller,PurgeRequest request)throws SQLException{
        try(var ps=tx.statement(c,"""
            SELECT policy_revision,hold_state,retain_until,observed_at,valid_until FROM fs.file_retention_evidence
            WHERE tenant_id=? AND file_id=? AND lifecycle_revision=? FOR SHARE
            """,caller.tenant().value(),request.file().value(),request.lifecycleRevision());var rows=ps.executeQuery()){
            if(!rows.next())return null;
            return new Evidence(request.lifecycleRevision(),rows.getLong(1),Hold.valueOf(rows.getString(2)),
                instant(rows,3),instant(rows,4),instant(rows,5));
        }
    }

    private boolean priorClaim(Connection c,Caller caller,PurgeRequest request)throws SQLException{
        try(var ps=tx.statement(c,"""
            SELECT EXISTS (SELECT 1 FROM fs.download_transfer_handle WHERE tenant_id=? AND file_id=? AND claimed_at IS NOT NULL)
            """,caller.tenant().value(),request.file().value());var rows=ps.executeQuery()){
            rows.next();return rows.getBoolean(1);
        }
    }
    private static PurgePreparation result(UUID operation,Target target,Evidence evidence){
        return new PurgePreparation(operation,target.file().reference(),target.version(),target.revision(),
            evidence.policyRevision(),PurgeDisposition.REVIEW_REQUIRED);
    }
    private static OffsetDateTime utc(Instant value){return value.atOffset(ZoneOffset.UTC);}
    private static Instant instant(ResultSet rows,int index)throws SQLException{
        var value=rows.getObject(index,OffsetDateTime.class);return value==null?null:value.toInstant();
    }
    private static String hash(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException ex){throw new IllegalStateException("digest unavailable");}
    }
    private static Failure missing(){return new Failure(Code.NOT_FOUND);}
    private static Failure conflict(){return new Failure(Code.CONFLICT);}
}
