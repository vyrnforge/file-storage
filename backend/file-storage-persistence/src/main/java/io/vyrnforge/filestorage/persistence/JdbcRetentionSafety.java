package io.vyrnforge.filestorage.persistence;

import io.vyrnforge.filestorage.domain.RetentionSafety;
import io.vyrnforge.filestorage.domain.RetentionSafety.*;
import io.vyrnforge.filestorage.domain.StorageModel.*;
import java.sql.*;
import java.time.*;
import java.util.Objects;
import javax.sql.DataSource;

/** Internal read-only assessment. Not wired to an API, purge worker or policy writer. */
public final class JdbcRetentionSafety {
    private final DataSource source;
    private final Clock clock;
    public JdbcRetentionSafety(DataSource source,Clock clock){
        this.source=Objects.requireNonNull(source);this.clock=Objects.requireNonNull(clock);
    }

    public Assessment assess(TenantId tenant,FileId file) {
        try(var c=source.getConnection();var ps=c.prepareStatement("""
                SELECT f.state,f.lifecycle_revision,e.policy_revision,e.hold_state,e.retain_until,e.observed_at,e.valid_until
                FROM fs.file f LEFT JOIN fs.file_retention_evidence e
                    ON e.tenant_id=f.tenant_id AND e.file_id=f.file_id AND e.lifecycle_revision=f.lifecycle_revision
                WHERE f.tenant_id=? AND f.file_id=?
                """)){
            ps.setQueryTimeout(5);ps.setObject(1,tenant.value());ps.setString(2,file.value());
            try(var rows=ps.executeQuery()){
                if(!rows.next())return Assessment.EVIDENCE_MISSING;
                var state=FileState.valueOf(rows.getString(1));long revision=rows.getLong(2);
                Long policy=rows.getObject(3,Long.class);
                Evidence evidence=policy==null?null:new Evidence(revision,policy,Hold.valueOf(rows.getString(4)),
                    instant(rows,5),instant(rows,6),instant(rows,7));
                return RetentionSafety.assess(state,revision,evidence,clock.instant());
            }
        }catch(SQLException|IllegalArgumentException ex){throw new JdbcTransactions.PersistenceFailure();}
    }
    private static Instant instant(ResultSet rows,int index)throws SQLException{
        var value=rows.getObject(index,OffsetDateTime.class);return value==null?null:value.toInstant();
    }
}
