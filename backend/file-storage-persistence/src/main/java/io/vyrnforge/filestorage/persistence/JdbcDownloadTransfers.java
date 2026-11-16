package io.vyrnforge.filestorage.persistence;

import io.vyrnforge.filestorage.application.FileApplication.*;
import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.platform.kernel.context.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import javax.sql.DataSource;

/** Exact clean-content download pins. No provider I/O or external authority in a transaction. */
public final class JdbcDownloadTransfers {
    private final DataSource source;
    private final JdbcUploadTransfers tx;
    private final Clock clock;
    private final Duration ttl;
    private final SecureRandom random=new SecureRandom();

    public JdbcDownloadTransfers(DataSource source,Clock clock,Duration ttl,int queryTimeout){
        this.source=Objects.requireNonNull(source);this.clock=Objects.requireNonNull(clock);
        this.ttl=Objects.requireNonNull(ttl);
        tx=new JdbcUploadTransfers(source,clock,ttl,queryTimeout);
    }
    public record Lookup(TenantId tenant,FileId file,VersionId version){}
    public record Issued(String handle,Instant expiresAt){
        @Override public String toString(){return "Issued[redacted]";}
    }
    public record Pin(Caller caller,FileId file,VersionId version,BackendId backend,long revision,
                      Locator locator,long size,String digest,Instant expiresAt,UUID readerToken){
        @Override public String toString(){return "DownloadPin[redacted]";}
    }
    private record Resource(Pin pin,String purpose,String ownerActor,String ownerConsumer,long lifecycleRevision){}

    public Issued issue(Caller caller,FileId file,VersionId version,CorrelationContext correlation){
        byte[] bytes=new byte[32];random.nextBytes(bytes);
        String handle=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return tx.execute((c,events,audits)->{
            var resource=resource(c,caller,file,version);
            lockGrant(c,resource);
            var pin=resource.pin();
            Instant expiry=tx.now().plus(ttl).truncatedTo(ChronoUnit.MICROS);
            if(!clock.instant().isBefore(expiry))throw denied();
            try(var ps=tx.statement(c,"""
                INSERT INTO fs.download_transfer_handle
                    (handle_hash,tenant_id,actor,consumer,file_id,version_id,location_id,backend_id,
                     config_revision,object_key,expected_size,sha256,expires_at,lifecycle_revision)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,hash(handle),caller.tenant().value(),caller.actor(),caller.consumer(),file.value(),version.value(),
                pin.locator().location().value(),pin.backend().value(),pin.revision(),pin.locator().objectKey(),
                pin.size(),pin.digest(),expiry.atOffset(ZoneOffset.UTC),resource.lifecycleRevision())){
                if(ps.executeUpdate()!=1)throw new Failure(Code.CONFLICT);
            }
            record(events,audits,correlation,pin,"issued");
            return new Issued(handle,expiry);
        });
    }

    public Lookup lookup(String handle){
        try(var c=source.getConnection();var ps=tx.statement(c,"""
                SELECT h.tenant_id,h.file_id,h.version_id FROM fs.download_transfer_handle h
                JOIN fs.file f ON f.tenant_id=h.tenant_id AND f.file_id=h.file_id
                WHERE h.handle_hash=? AND h.claimed_at IS NULL AND h.expires_at>?
                  AND f.state='ACTIVE' AND f.lifecycle_revision=h.lifecycle_revision
                """,hash(handle),tx.now().atOffset(ZoneOffset.UTC));var rows=ps.executeQuery()){
            if(!rows.next())throw denied();
            return new Lookup(new TenantId(rows.getObject(1,UUID.class)),new FileId(rows.getString(2)),
                new VersionId(rows.getObject(3,UUID.class)));
        }catch(SQLException ex){throw new JdbcTransactions.PersistenceFailure();}
    }

    public Pin claim(String handle,Caller caller,FileId file,VersionId version,CorrelationContext correlation){
        return tx.execute((c,events,audits)->{
            var resource=resource(c,caller,file,version);
            lockGrant(c,resource);
            var pin=resource.pin();var now=tx.now().atOffset(ZoneOffset.UTC);
            try(var ps=tx.statement(c,"""
                    UPDATE fs.download_transfer_handle SET claimed_at=?
                    WHERE handle_hash=? AND tenant_id=? AND actor=? AND consumer=? AND file_id=? AND version_id=?
                      AND location_id=? AND backend_id=? AND config_revision=? AND object_key=? AND expected_size=?
                      AND sha256=? AND lifecycle_revision=? AND claimed_at IS NULL AND expires_at>? RETURNING expires_at
                    """,now,hash(handle),caller.tenant().value(),caller.actor(),caller.consumer(),file.value(),version.value(),
                    pin.locator().location().value(),pin.backend().value(),pin.revision(),pin.locator().objectKey(),
                    pin.size(),pin.digest(),resource.lifecycleRevision(),now);var rows=ps.executeQuery()){
                if(!rows.next())throw denied();
                Instant expiry=rows.getObject(1,OffsetDateTime.class).toInstant();
                if(!clock.instant().isBefore(expiry))throw denied();
                UUID readerToken=io.vyrnforge.platform.kernel.ids.UuidV7.generate();
                try(var lease=tx.statement(c,"""
                    INSERT INTO fs.download_reader(handle_hash,lease_token,claimed_at,expires_at) VALUES (?,?,?,?)
                    """,hash(handle),readerToken,now,expiry.atOffset(ZoneOffset.UTC))){
                    if(lease.executeUpdate()!=1)throw denied();
                }
                record(events,audits,correlation,pin,"claimed");
                return new Pin(caller,file,version,pin.backend(),pin.revision(),pin.locator(),pin.size(),pin.digest(),expiry,readerToken);
            }
        });
    }

    /** Trusted gateway acknowledgement after successful stream AND provider lease close.
     * Late acknowledgement is valid positive evidence; expiry alone never drains a reader.
     */
    public boolean acknowledge(TenantId tenant,FileId file,VersionId version,UUID token){
        Objects.requireNonNull(token);
        return tx.execute((c,events,audits)->{
            try(var ps=tx.statement(c,"""
                UPDATE fs.download_reader d SET drained_at=?
                FROM fs.download_transfer_handle h
                WHERE d.handle_hash=h.handle_hash AND d.lease_token=? AND d.drained_at IS NULL
                  AND h.tenant_id=? AND h.file_id=? AND h.version_id=? AND d.claimed_at<=?
                """,tx.now().atOffset(ZoneOffset.UTC),token,tenant.value(),file.value(),version.value(),tx.now().atOffset(ZoneOffset.UTC))){
                if(ps.executeUpdate()==1)return true;
            }
            try(var ps=tx.statement(c,"""
                SELECT 1 FROM fs.download_reader d JOIN fs.download_transfer_handle h USING(handle_hash)
                WHERE d.lease_token=? AND h.tenant_id=? AND h.file_id=? AND h.version_id=? AND d.drained_at IS NOT NULL
                """,token,tenant.value(),file.value(),version.value());var rows=ps.executeQuery()){
                return rows.next();
            }
        });
    }

    private Resource resource(Connection c,Caller caller,FileId file,VersionId version)throws SQLException{
        // V5 immutable original pin + V6 measured integrity + V7 clean scan + succeeded scan operation.
        try(var ps=tx.statement(c,"""
                SELECT s.backend_id,s.config_revision,v.location_id,v.object_key,e.observed_size,e.observed_sha256,
                       s.purpose,r.actor,s.consumer,f.lifecycle_revision
                FROM fs.file f JOIN fs.file_version v ON v.tenant_id=f.tenant_id AND v.file_id=f.file_id
                JOIN fs.upload_session s ON s.tenant_id=v.tenant_id AND s.file_id=v.file_id
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
                JOIN fs.backend_revision b ON b.backend_id=s.backend_id AND b.config_revision=s.config_revision
                WHERE f.tenant_id=? AND f.file_id=? AND v.version_id=? AND f.state='ACTIVE'
                    AND v.state='AVAILABLE' AND s.state='COMPLETED' AND e.verdict='CLEAN'
                    AND i.verified_at IS NOT NULL AND e.observed_size=h.expected_size
                    AND e.detected_mime_type=r.declared_mime_type
                    AND b.status IN ('ACTIVE','DRAINING') AND b.validated_revision=b.config_revision
                FOR SHARE OF f,v,s,r,h,i,e,o,b
                """,caller.tenant().value(),file.value(),version.value());var rows=ps.executeQuery()){
            if(!rows.next())throw denied();
            var pin=new Pin(caller,file,version,new BackendId(rows.getObject(1,UUID.class)),rows.getLong(2),
                new Locator(new LocationId(rows.getObject(3,UUID.class)),rows.getString(4),null),
                rows.getLong(5),rows.getString(6),Instant.EPOCH,null);
            return new Resource(pin,rows.getString(7),rows.getString(8),rows.getString(9),rows.getLong(10));
        }
    }

    private void lockGrant(Connection c,Resource resource)throws SQLException{
        var pin=resource.pin();var caller=pin.caller();var now=tx.now().atOffset(ZoneOffset.UTC);
        boolean owner=caller.actor().equals(resource.ownerActor())&&caller.consumer().equals(resource.ownerConsumer());
        try(var ps=tx.statement(c,"""
                SELECT expires_at FROM fs.file_grant WHERE tenant_id=? AND actor=? AND consumer=? AND purpose=?
                  AND action='DOWNLOAD_TRANSFER' AND NOT revoked AND not_before<=? AND expires_at>?
                  AND ((scope='OWNED_FILE' AND ?) OR (scope='EXACT_FILE' AND file_id=?))
                ORDER BY grant_id LIMIT 1 FOR SHARE
                """,caller.tenant().value(),caller.actor(),caller.consumer(),resource.purpose(),now,now,owner,pin.file().value());
            var rows=ps.executeQuery()){
            if(!rows.next()||!clock.instant().isBefore(rows.getObject(1,OffsetDateTime.class).toInstant()))throw denied();
        }
    }

    private void record(io.vyrnforge.platform.kernel.event.EventIntentPort events,
            io.vyrnforge.platform.kernel.audit.AuditIntentPort audits,CorrelationContext correlation,Pin pin,String action){
        MutationIntents.record(events,audits,clock,correlation,new ActorReference(ActorType.USER,pin.caller().actor(),null,null),
            pin.caller().tenant().value(),"file-storage.transfer.download."+action,"file-storage.file",pin.file().value(),
            Map.of("versionId",pin.version().value().toString()));
    }
    private static Failure denied(){return new Failure(Code.DENIED);}
    private static byte[] hash(String handle){
        try{
            if(handle==null||!handle.matches("[A-Za-z0-9_-]{43}"))throw denied();
            byte[] raw=Base64.getUrlDecoder().decode(handle);
            if(raw.length!=32||!Base64.getUrlEncoder().withoutPadding().encodeToString(raw).equals(handle))throw denied();
            return MessageDigest.getInstance("SHA-256").digest(raw);
        }catch(IllegalArgumentException|NoSuchAlgorithmException ex){throw denied();}
    }
}
