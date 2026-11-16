package io.vyrnforge.filestorage.persistence;

import io.vyrnforge.filestorage.application.FileApplication.Code;
import io.vyrnforge.filestorage.application.FileApplication.Failure;
import io.vyrnforge.filestorage.domain.StorageModel.TenantId;
import io.vyrnforge.platform.kernel.context.ActorReference;
import io.vyrnforge.platform.kernel.context.ActorType;
import io.vyrnforge.platform.kernel.context.CorrelationContext;
import io.vyrnforge.platform.kernel.ids.UuidV7;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Restricted internal manifest command adapter. No accounting execution or provider I/O. */
public final class JdbcAccountingManifest {
    public enum Disposition { ACKNOWLEDGED, NEEDS_INVESTIGATION }
    private final JdbcUploadTransfers transactions;
    private final Clock clock;

    public JdbcAccountingManifest(DataSource source, Clock clock) {
        this.clock=Objects.requireNonNull(clock);
        this.transactions=new JdbcUploadTransfers(source,clock,Duration.ofMinutes(1),6);
    }

    public UUID capture(TenantId tenant, UUID id, long revision, CorrelationContext correlation) {
        return command(tenant,id,revision,null,correlation);
    }

    public UUID review(TenantId tenant, UUID id, long revision, Disposition decision, CorrelationContext correlation) {
        return command(tenant,id,revision,Objects.requireNonNull(decision),correlation);
    }

    private UUID command(TenantId tenant, UUID id, long revision, Disposition decision, CorrelationContext correlation) {
        Objects.requireNonNull(tenant); Objects.requireNonNull(id); Objects.requireNonNull(correlation);
        if(revision<1) throw new Failure(Code.INVALID);
        return transactions.execute((c,events,audits)->{
            UUID auditId=UuidV7.generate(), eventId=UuidV7.generate();
            String actor; boolean created;
            try(var ps=transactions.statement(c,"SELECT actor_id,created FROM fs.audited_accounting_manifest(?,?,?,?,?,?,?,?)",
                    tenant.value(),id,revision,decision==null?"CAPTURE":"REVIEW",decision==null?null:decision.name(),
                    auditId,eventId,correlation.correlationId());var rows=ps.executeQuery()){
                if(!rows.next())throw new Failure(Code.CONFLICT);
                actor=rows.getString(1);created=rows.getBoolean(2);
            }catch(SQLException ex){
                if("42501".equals(ex.getSQLState()))throw new Failure(Code.DENIED);
                throw ex;
            }
            if(created){
                var payload=new HashMap<String,Object>();
                payload.put("policyRevision",revision);payload.put("mode","DRY_RUN_ONLY");
                if(decision!=null)payload.put("disposition",decision.name());
                MutationIntents.record(events,audits,clock,correlation,new ActorReference(ActorType.USER,actor,null,null),
                        tenant.value(),"file-storage.accounting.manifest-"+(decision==null?"captured":"reviewed"),
                        "file-storage.accounting-manifest",id.toString(),payload,auditId,eventId);
            }
            return id;
        });
    }
}
