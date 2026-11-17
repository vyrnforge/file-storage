package io.vyrnforge.filestorage.runtime;

import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

/** Explicit isolated deployment bindings. No credentials in config records/logs.
 * Identity admission is a necessary check, not a substitute for reviewed effective ACLs.
 */
public final class RuntimeDatabaseRoles {
    public enum Role { METADATA, LOGICAL, PURGE_PREPARATION, PLACEMENT, BACKEND, GRANT_READ,
        READINESS, TRANSFER, WORKER, GOVERNANCE, DISPATCHER }
    public record Binding(String login,DataSource source){
        public Binding{
            Objects.requireNonNull(source);
            if(login==null||!login.matches("[a-z_][a-z0-9_]{0,62}"))throw new IllegalArgumentException("invalid database binding");
        }
        @Override public String toString(){return "Binding[redacted]";}
    }
    private final Map<Role,Binding> bindings;
    public RuntimeDatabaseRoles(Map<Role,Binding> bindings){
        this.bindings=Map.copyOf(bindings);
        if(!this.bindings.keySet().equals(EnumSet.allOf(Role.class))
            ||this.bindings.values().stream().map(Binding::login).distinct().count()!=Role.values().length)
            throw new IllegalArgumentException("complete distinct database roles required");
    }
    public DataSource source(Role role){return bindings.get(role).source();}
    @Override public String toString(){return "RuntimeDatabaseRoles[redacted]";}
    private record Database(String name,long oid,String address,int port){}
    /** Check each actual login, no SET ROLE, no privileged attributes or ownership path.
     * Database tuple is a same-target sanity check, not cryptographic cluster identity.
     * Fail closed on any error, including missing schema, revoked login or connection failure.
     */
    public boolean ready(){
        Database expected=null;
        try{
            for(Role role:Role.values()){
                var binding=bindings.get(role);
                try(var c=binding.source().getConnection();var st=c.createStatement()){
                    st.setQueryTimeout(5);
                    try(var rs=st.executeQuery("""
                        SELECT session_user,current_user,current_database(),d.oid,
                          coalesce(inet_server_addr()::text,'local'),coalesce(inet_server_port(),0),
                          r.rolsuper,r.rolcreatedb,r.rolcreaterole,r.rolreplication,r.rolbypassrls,
                          pg_has_role(r.oid,d.datdba,'MEMBER'),
                          EXISTS(SELECT 1 FROM pg_namespace n WHERE n.nspname IN ('fs','fs_kernel')
                            AND (pg_has_role(r.oid,n.nspowner,'MEMBER') OR has_schema_privilege(r.oid,n.oid,'CREATE'))),
                          (SELECT count(*) FROM pg_namespace WHERE nspname IN ('fs','fs_kernel')),
                          has_database_privilege(r.oid,d.oid,'CREATE'),
                          EXISTS(SELECT 1 FROM pg_roles p WHERE p.oid<>r.oid AND pg_has_role(r.oid,p.oid,'MEMBER')
                            AND (p.rolsuper OR p.rolcreatedb OR p.rolcreaterole OR p.rolreplication OR p.rolbypassrls
                              OR p.rolname IN ('pg_read_all_data','pg_write_all_data','pg_execute_server_program','pg_read_server_files','pg_write_server_files')))
                        FROM pg_roles r JOIN pg_database d ON d.datname=current_database()
                        WHERE r.rolname=session_user
                        """)){
                        if(!rs.next()||!binding.login().equals(rs.getString(1))||!binding.login().equals(rs.getString(2)))return false;
                        for(int i=7;i<=13;i++)if(rs.getBoolean(i))return false;
                        if(rs.getInt(14)!=2||rs.getBoolean(15)||rs.getBoolean(16))return false;
                        var target=new Database(rs.getString(3),rs.getLong(4),rs.getString(5),rs.getInt(6));
                        if(expected==null)expected=target;else if(!expected.equals(target))return false;
                    }
                    // Required per-duty sentinel and universal prohibition on schema history
                    // writes. Exact ACL inventory remains an operator-reviewed admission input.
                    String required=switch(role){
                        case METADATA -> "has_table_privilege(current_user,'fs.file','INSERT')";
                        case LOGICAL -> "has_column_privilege(current_user,'fs.file','state','UPDATE')";
                        case PURGE_PREPARATION -> "has_table_privilege(current_user,'fs.purge_preparation','INSERT')";
                        case PLACEMENT -> "has_table_privilege(current_user,'fs.policy_revision','INSERT')";
                        case BACKEND -> "has_table_privilege(current_user,'fs.backend','INSERT')";
                        case GRANT_READ -> "has_table_privilege(current_user,'fs.file_grant','SELECT')";
                        case READINESS -> "has_table_privilege(current_user,'fs.flyway_schema_history','SELECT') AND has_table_privilege(current_user,'fs_kernel.flyway_schema_history','SELECT')";
                        case TRANSFER -> "has_column_privilege(current_user,'fs.upload_transfer_handle','claimed_at','UPDATE')";
                        case WORKER -> "has_column_privilege(current_user,'fs.storage_operation','status','UPDATE')";
                        case GOVERNANCE -> "has_table_privilege(current_user,'fs.retention_policy_draft','INSERT')";
                        case DISPATCHER -> "has_schema_privilege(current_user,'fs_kernel','USAGE') AND NOT has_schema_privilege(current_user,'fs','USAGE') AND has_column_privilege(current_user,'fs_kernel.platform_event_outbox','status','UPDATE')";
                    };
                    // Table-level ACLs alone do not see column-only INSERT/UPDATE or
                    // privileges accessible through a non-inheriting membership/SET ROLE path.
                    try(var rs=st.executeQuery("SELECT ("+required+") AND ("+HISTORY_WRITE_DENIED+")")){
                        if(!rs.next()||!rs.getBoolean(1))return false;
                    }
                }
            }
            return io.vyrnforge.filestorage.persistence.StorageMigrations.ready(source(Role.READINESS));
        }catch(SQLException|RuntimeException ex){return false;}
    }
    private static final String HISTORY_WRITE_DENIED = """
        SELECT count(*)=2 AND bool_and(NOT EXISTS(
            SELECT 1 FROM pg_roles p
            WHERE pg_has_role(current_user,p.oid,'MEMBER')
              AND (p.oid=r.relowner
                   OR has_table_privilege(p.oid,r.oid,'INSERT,UPDATE,DELETE,TRUNCATE')
                   OR has_any_column_privilege(p.oid,r.oid,'INSERT,UPDATE'))))
        FROM pg_class r JOIN pg_namespace n ON n.oid=r.relnamespace
        WHERE n.nspname IN ('fs','fs_kernel') AND r.relname='flyway_schema_history'
        """;

    public void requireReady(){if(!ready())throw new IllegalStateException("separated database admission failed");}
}
