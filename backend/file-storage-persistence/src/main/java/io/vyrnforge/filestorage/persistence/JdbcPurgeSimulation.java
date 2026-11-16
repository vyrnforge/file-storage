package io.vyrnforge.filestorage.persistence;

import io.vyrnforge.filestorage.application.FileApplication.*;
import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.platform.kernel.context.*;
import io.vyrnforge.platform.kernel.ids.UuidV7;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;

/** Internal simulation only. No provider, worker scheduler, or executable purge capability. */
public final class JdbcPurgeSimulation {
    private final JdbcUploadTransfers tx;
    private final JdbcPurgePreparation preparation;
    private final Clock clock;
    private final Duration lease;
    private final int maxAttempts;
    public JdbcPurgeSimulation(DataSource source,Clock clock,Duration lease,int maxAttempts){
        this.clock=Objects.requireNonNull(clock);this.lease=Objects.requireNonNull(lease);
        if(lease.compareTo(Duration.ofMillis(1))<0||lease.compareTo(Duration.ofMinutes(5))>0||maxAttempts<1||maxAttempts>10)
            throw new IllegalArgumentException("invalid simulation bounds");
        this.maxAttempts=maxAttempts;
        tx=new JdbcUploadTransfers(source,clock,Duration.ofMinutes(1),5);
        preparation=new JdbcPurgePreparation(source,clock);
    }
    public record Claim(TenantId tenant,UUID operation,UUID token,Instant expiresAt,int attempt){
        @Override public String toString(){return "SimulationClaim[redacted]";}
    }
    public Claim claim(TenantId tenant,UUID operation){
        Objects.requireNonNull(tenant);Objects.requireNonNull(operation);
        return tx.execute((c,events,audits)->{
            preparation.validateSimulation(c,tenant,operation);
            var now=tx.now();var until=now.plus(lease).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            UUID token=UuidV7.generate();
            try(var ps=tx.statement(c,"""
                INSERT INTO fs.purge_simulation(operation_id,claim_token,attempts,max_attempts,claimed_at,locked_until,status)
                VALUES (?,?,1,?,?,?,'CLAIMED')
                ON CONFLICT(operation_id) DO UPDATE SET claim_token=EXCLUDED.claim_token,
                  attempts=fs.purge_simulation.attempts+1,claimed_at=EXCLUDED.claimed_at,locked_until=EXCLUDED.locked_until
                WHERE fs.purge_simulation.status='CLAIMED' AND fs.purge_simulation.locked_until<=EXCLUDED.claimed_at
                  AND fs.purge_simulation.attempts<fs.purge_simulation.max_attempts
                  AND fs.purge_simulation.max_attempts=EXCLUDED.max_attempts
                RETURNING attempts
                """,operation,token,maxAttempts,now.atOffset(ZoneOffset.UTC),until.atOffset(ZoneOffset.UTC));var rows=ps.executeQuery()){
                if(!rows.next())throw new Failure(Code.CONFLICT);
                return new Claim(tenant,operation,token,until,rows.getInt(1));
            }
        });
    }

    /** Recheck exact current evidence and drain under file lock, then fence the simulated result.
     * No external effect occurs between claim and finish. An expired or replaced token cannot finish.
     */
    public boolean complete(Claim claim){
        Objects.requireNonNull(claim);
        return tx.execute((c,events,audits)->{
            var file=preparation.validateSimulation(c,claim.tenant(),claim.operation());
            var now=tx.now().atOffset(ZoneOffset.UTC);
            try(var ps=tx.statement(c,"""
                UPDATE fs.purge_simulation SET status='SIMULATED',finished_at=?
                WHERE operation_id=? AND claim_token=? AND attempts=? AND status='CLAIMED' AND locked_until>?
                """,now,claim.operation(),claim.token(),claim.attempt(),now)){
                if(ps.executeUpdate()!=1)return false;
            }
            MutationIntents.record(events,audits,clock,CorrelationContext.create(),
                new ActorReference(ActorType.SERVICE,"file-storage-purge-simulation",null,null),claim.tenant().value(),
                "file-storage.file.purge-simulated","file-storage.file",file.value(),
                Map.of("operationId",claim.operation().toString(),"disposition","SIMULATED"));
            return true;
        });
    }
}
