package io.vyrnforge.filestorage.runtime;

import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Version 1 candidate profiles: disposable rehearsal only, never invoked by runtime or Flyway. */
final class IsolatedRoleProfiles {
    static final int VERSION=1;
    enum Kind { INITIATOR, LIFECYCLE, MANIFEST, DISPATCHER }
    record Grant(String object,String privilege,List<String> columns) {}
    private final Kind kind;
    private final List<Grant> grants=new ArrayList<>();
    private final Set<String> functions=new HashSet<>();
    private final Set<String> schemas=new HashSet<>();

    IsolatedRoleProfiles(Kind kind){
        this.kind=kind;
        schemas.add("fs_kernel");
        if(kind!=Kind.DISPATCHER)schemas.add("fs");
        if(kind!=Kind.DISPATCHER){
            table("SELECT","fs_kernel.platform_audit_event","fs_kernel.platform_event_outbox");
            table("INSERT","fs_kernel.platform_audit_event","fs_kernel.platform_event_outbox");
            column("UPDATE","fs_kernel.platform_event_outbox","event_id");
        }
        switch(kind){
            case INITIATOR -> {
                readFs("backend","backend_revision","storage_location","active_policy","policy_rule","policy_revision",
                    "file","file_version","upload_session","initiation_request","file_grant","upload_scan_evidence",
                    "upload_transfer_handle","isolated_quota_fence","isolated_quota_reservation","isolated_policy_activation");
                column("UPDATE","fs.backend","endpoint");
                table("INSERT","fs.file","fs.file_version","fs.upload_session","fs.initiation_request","fs.isolated_quota_reservation");
                column("INSERT","fs.isolated_quota_fence","tenant_id");
                column("UPDATE","fs.isolated_quota_fence","epoch");
                functions.add("fs.touch_quota_fence(uuid)");
                for(String p:List.of("SELECT","INSERT","UPDATE","DELETE"))table(p,"fs_kernel.platform_idempotency");
            }
            case LIFECYCLE -> {
                readFs("file","file_version","upload_session","initiation_request","backend","backend_revision",
                    "storage_location","file_grant","upload_transfer_handle","upload_integrity_verification","upload_scan_evidence",
                    "upload_terminal_recovery","storage_operation","isolated_quota_reservation","isolated_quota_fence","isolated_policy_activation");
                column("UPDATE","fs.file","file_id");
                for(String t:List.of("initiation_request","upload_integrity_verification","upload_transfer_handle"))
                    column("UPDATE","fs."+t,"session_id");
                column("UPDATE","fs.backend_revision","backend_id");
                column("UPDATE","fs.file_grant","grant_id");
                column("UPDATE","fs.backend","endpoint");
                column("UPDATE","fs.file_version","state");column("UPDATE","fs.upload_session","state");
                table("INSERT","fs.storage_operation","fs.upload_scan_evidence","fs.upload_terminal_recovery");
                column("UPDATE","fs.storage_operation","status","last_error_code","finished_at","available_at","claim_token","locked_until","attempts");
            }
            case MANIFEST -> {
                functions.add("fs.audited_accounting_manifest(uuid,uuid,bigint,text,text,uuid,uuid,uuid)");
                functions.add("fs.read_accounting_manifest(uuid,uuid)");
            }
            case DISPATCHER -> {
                table("SELECT","fs_kernel.platform_event_outbox");
                column("UPDATE","fs_kernel.platform_event_outbox","status","last_error_code","published_at","available_at",
                    "locked_until","claim_token","attempts");
            }
        }
    }
    private void readFs(String... tables){for(String t:tables)table("SELECT","fs."+t);}
    private void table(String p,String... tables){for(String t:tables)grants.add(new Grant(t,p,List.of()));}
    private void column(String p,String t,String... c){grants.add(new Grant(t,p,List.of(c)));}
    private static String safe(String name){
        if(!name.matches("a12_[a-z0-9_]+"))throw new IllegalArgumentException("fixture role required");
        return name;
    }
    static void exec(DataSource owner,String sql)throws SQLException{
        try(var c=owner.getConnection();var st=c.createStatement()){st.execute(sql);}
    }
    void provision(DataSource owner,String group)throws SQLException{
        group=safe(group);
        try(var c=owner.getConnection();var st=c.createStatement()){
            c.setAutoCommit(false);
            try{
                st.execute("CREATE ROLE "+group+" NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
                for(String s:schemas)st.execute("GRANT USAGE ON SCHEMA "+s+" TO "+group);
                for(Grant g:grants)st.execute("GRANT "+g.privilege()+(g.columns().isEmpty()?"":"("+String.join(",",g.columns())+")")+" ON "+g.object()+" TO "+group);
                for(String f:functions)st.execute("GRANT EXECUTE ON FUNCTION "+f+" TO "+group);
                c.commit();
            }catch(SQLException ex){c.rollback();throw ex;}
        }
    }
    private boolean allowed(String object,String privilege,String col){
        return grants.stream().anyMatch(g->g.object().equals(object)&&g.privilege().equals(privilege)
            &&(g.columns().isEmpty()||(col!=null&&g.columns().contains(col))));
    }
    // Exact effective table/column matrix (includes PUBLIC and inherited grants).
    // Separately check all membership edges, even NOINHERIT/SET ROLE paths.
    List<String> drift(DataSource owner,String login,String group)throws SQLException{
        safe(login);safe(group);var result=new ArrayList<String>();
        try(var c=owner.getConnection()){
            try(var ps=c.prepareStatement("""
                SELECT r.rolname,r.rolsuper,r.rolcreatedb,r.rolcreaterole,r.rolreplication,r.rolbypassrls,r.rolcanlogin,
                  r.rolinherit, (SELECT count(*) FROM pg_auth_members m WHERE m.member=r.oid),
                  EXISTS(SELECT 1 FROM pg_auth_members m JOIN pg_roles p ON p.oid=m.roleid
                    WHERE m.member=r.oid AND p.rolname=? AND m.inherit_option AND NOT m.admin_option)
                FROM pg_roles r WHERE r.rolname IN (?,?)
                """)){
                ps.setString(1,group);ps.setString(2,login);ps.setString(3,group);
                int seen=0;
                try(var rows=ps.executeQuery()){while(rows.next()){
                    seen++;boolean isLogin=login.equals(rows.getString(1));
                    if(rows.getBoolean(2)||rows.getBoolean(3)||rows.getBoolean(4)||rows.getBoolean(5)||rows.getBoolean(6)
                        ||rows.getBoolean(7)!=isLogin||!rows.getBoolean(8)
                        ||rows.getLong(9)!=(isLogin?1:0)||(isLogin&&!rows.getBoolean(10)))
                        result.add("role/membership "+rows.getString(1));
                }}
                if(seen!=2)result.add("missing role");
            }
            try(var ps=c.prepareStatement("""
                SELECT n.nspname,has_schema_privilege(?,n.oid,'USAGE'),has_schema_privilege(?,n.oid,'CREATE')
                FROM pg_namespace n WHERE n.nspname IN ('fs','fs_kernel')
                """)){
                ps.setString(1,login);ps.setString(2,login);
                try(var rows=ps.executeQuery()){while(rows.next())
                    if(rows.getBoolean(2)!=schemas.contains(rows.getString(1))||rows.getBoolean(3))
                        result.add("schema "+rows.getString(1));}
            }
            try(var ps=c.prepareStatement("""
                SELECT n.nspname||'.'||r.relname,p.priv,has_table_privilege(?,r.oid,p.priv),has_table_privilege(?,r.oid,p.priv||' WITH GRANT OPTION')
                FROM pg_class r JOIN pg_namespace n ON n.oid=r.relnamespace
                CROSS JOIN unnest(ARRAY['SELECT','INSERT','UPDATE','DELETE','TRUNCATE','REFERENCES','TRIGGER','MAINTAIN']) p(priv)
                WHERE n.nspname IN ('fs','fs_kernel') AND r.relkind IN ('r','p','v','m','f')
                """)){
                ps.setString(1,login);ps.setString(2,login);
                try(var rows=ps.executeQuery()){while(rows.next())
                    if(rows.getBoolean(3)!=allowed(rows.getString(1),rows.getString(2),null)||rows.getBoolean(4))
                        result.add("table "+rows.getString(1)+" "+rows.getString(2));}
            }
            try(var ps=c.prepareStatement("""
                SELECT n.nspname||'.'||r.relname,a.attname,p.priv,has_column_privilege(?,r.oid,a.attnum,p.priv)
                FROM pg_class r JOIN pg_namespace n ON n.oid=r.relnamespace
                JOIN pg_attribute a ON a.attrelid=r.oid AND a.attnum>0 AND NOT a.attisdropped
                CROSS JOIN unnest(ARRAY['SELECT','INSERT','UPDATE','REFERENCES']) p(priv)
                WHERE n.nspname IN ('fs','fs_kernel') AND r.relkind IN ('r','p','v','m','f')
                """)){
                ps.setString(1,login);
                try(var rows=ps.executeQuery()){while(rows.next())
                    if(rows.getBoolean(4)!=allowed(rows.getString(1),rows.getString(3),rows.getString(2)))
                        result.add("column "+rows.getString(1)+"."+rows.getString(2)+" "+rows.getString(3));}
            }
            // All non-trigger service functions: compare by OID to the exact approved signatures.
            // Trigger routines cannot be called as ordinary SQL functions.
            try(var ps=c.prepareStatement("""
                SELECT p.oid, n.nspname||'.'||p.proname,has_function_privilege(?,p.oid,'EXECUTE')
                FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
                WHERE n.nspname IN ('fs','fs_kernel') AND p.prorettype<> 'trigger'::regtype
                """)){
                ps.setString(1,login);var expected=new HashSet<Long>();
                var baseline=new HashSet<>(functions);
                // V4 legacy PUBLIC invoker helper; it has no elevated privileges and
                // callers still need schema/table access. Track it explicitly, not a wildcard.
                baseline.add("fs.backend_retirement_blocked(uuid)");
                for(String f:baseline)try(var q=c.prepareStatement("SELECT ?::regprocedure::oid")){q.setString(1,f);try(var row=q.executeQuery()){row.next();expected.add(row.getLong(1));}}
                try(var rows=ps.executeQuery()){while(rows.next())
                    if(rows.getBoolean(3)!=expected.contains(rows.getLong(1)))
                        result.add("function "+rows.getString(2));}
            }
        }
        return result;
    }
    void verify(DataSource owner,String login,String group)throws SQLException{
        assertEquals(List.of(),drift(owner,login,group),kind+" effective privilege drift");
    }
}
