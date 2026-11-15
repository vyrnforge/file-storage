package io.vyrnforge.filestorage.persistence;

import io.vyrnforge.filestorage.application.FileApplication.*;
import io.vyrnforge.filestorage.domain.StorageModel.*;
import java.sql.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** One snapshot for visible logical records; authorization is applied before limit or aggregation.
 * The external trusted scope is intersected with current local grants, never unioned.
 */
final class JdbcCatalog {
    private JdbcCatalog(){}
    private static final int QUERY_TIMEOUT_SECONDS=5;

    private static final String VISIBLE="""
        SELECT f.file_id,f.tenant_id,f.name,f.state AS file_state,f.lifecycle_revision,v.version_id,v.state AS version_state,
               r.expected_size,r.declared_mime_type,
               CASE WHEN v.state='AVAILABLE' AND e.verdict='CLEAN' THEN e.observed_size ELSE 0 END AS available_size
        FROM fs.file f
        JOIN fs.upload_session s ON s.tenant_id=f.tenant_id AND s.file_id=f.file_id
        JOIN fs.file_version v ON v.tenant_id=s.tenant_id AND v.file_id=s.file_id AND v.version_id=s.version_id
        JOIN fs.initiation_request r ON r.tenant_id=s.tenant_id AND r.session_id=s.session_id AND r.consumer=s.consumer
        LEFT JOIN fs.upload_scan_evidence e ON e.session_id=s.session_id
        WHERE f.tenant_id=? AND f.state='ACTIVE'
          AND EXISTS (SELECT 1 FROM allowed a WHERE a.purpose=s.purpose AND a.expires_at>?
                    AND ((a.file_id IS NULL AND r.actor=? AND s.consumer=?) OR a.file_id=f.file_id))
          AND EXISTS (SELECT 1 FROM fs.file_grant g WHERE g.tenant_id=f.tenant_id
                    AND g.actor=? AND g.consumer=? AND g.purpose=s.purpose AND g.action='LIST_METADATA'
                    AND NOT g.revoked AND g.not_before<=? AND g.expires_at>?
                    AND ((g.scope='OWNED_FILE' AND r.actor=? AND s.consumer=?)
                         OR (g.scope='EXACT_FILE' AND g.file_id=f.file_id)))
        """;

    private static PreparedStatement statement(Connection c,Caller caller,List<CatalogGrant> grants,Instant now,
                                                String suffix,Object... tail)throws SQLException{
        if(grants.isEmpty()||grants.size()>256)throw new Failure(Code.DENIED);
        String values=String.join(",",Collections.nCopies(grants.size(),"(CAST(? AS text),CAST(? AS text),CAST(? AS timestamptz))"));
        var args=new ArrayList<Object>();
        for(var grant:grants){
            args.add(grant.purpose());args.add(grant.file()==null?null:grant.file().value());
            // Floor expiry to microseconds conservatively for comparison with current time.
            args.add(grant.expiresAt().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC));
        }
        var timestamp=now.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
        args.addAll(List.of(caller.tenant().value(),timestamp,caller.actor(),caller.consumer(),caller.actor(),
            caller.consumer(),timestamp,timestamp,caller.actor(),caller.consumer()));
        args.addAll(Arrays.asList(tail));
        var ps=c.prepareStatement("WITH allowed(purpose,file_id,expires_at) AS (VALUES "+values+"), visible AS ("+VISIBLE+") "+suffix);
        try{
            ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            for(int i=0;i<args.size();i++)ps.setObject(i+1,args.get(i));
            return ps;
        }catch(SQLException|RuntimeException ex){ps.close();throw ex;}
    }

    static CatalogPage page(Connection c,Caller caller,List<CatalogGrant> grants,FileId after,int limit,Instant now)throws SQLException{
        if(limit<1||limit>100)throw new Failure(Code.INVALID);
        String sql="SELECT * FROM visible WHERE (CAST(? AS text) IS NULL OR file_id COLLATE \"C\">CAST(? AS text) COLLATE \"C\") "
            +"ORDER BY file_id COLLATE \"C\" LIMIT ?";
        String cursor=after==null?null:after.value();
        try(var ps=statement(c,caller,grants,now,sql,cursor,cursor,limit+1);var rows=ps.executeQuery()){
            var result=new ArrayList<MetadataView>();
            while(rows.next()){
                var file=new File(new FileId(rows.getString("file_id")),caller.tenant(),rows.getString("name"),
                    FileState.valueOf(rows.getString("file_state")));
                result.add(new MetadataView(file.reference(),new VersionId(rows.getObject("version_id",UUID.class)),
                    file.state(),VersionState.valueOf(rows.getString("version_state")),file.name(),
                    rows.getLong("expected_size"),rows.getString("declared_mime_type"),rows.getLong("lifecycle_revision")));
            }
            FileId next=null;
            if(result.size()>limit){
                result.removeLast();
                next=new FileId(result.getLast().file().fileId());
            }
            return new CatalogPage(result,next);
        }
    }

    static CatalogUsage usage(Connection c,Caller caller,List<CatalogGrant> grants,Instant now)throws SQLException{
        try(var ps=statement(c,caller,grants,now,"""
                SELECT version_state,count(*) AS count,sum(expected_size) AS declared,sum(available_size) AS available
                FROM visible GROUP BY version_state
                """);var rows=ps.executeQuery()){
            Map<VersionState,UsageBucket> states=new EnumMap<>(VersionState.class);
            while(rows.next()){
                var state=VersionState.valueOf(rows.getString(1));
                states.put(state,new UsageBucket(state,rows.getLong(2),rows.getBigDecimal(3).toBigIntegerExact().toString(),
                    rows.getBigDecimal(4).toBigIntegerExact().toString()));
            }
            var result=new ArrayList<UsageBucket>();
            for(var state:VersionState.values())result.add(states.getOrDefault(state,new UsageBucket(state,0,"0","0")));
            return new CatalogUsage(result);
        }
    }
}
