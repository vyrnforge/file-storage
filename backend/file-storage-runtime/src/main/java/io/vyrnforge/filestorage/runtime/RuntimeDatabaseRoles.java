package io.vyrnforge.filestorage.runtime;

import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

/** Explicit isolated deployment bindings. No credentials in config records/logs.
 * Identity admission is a necessary check, not a substitute for reviewed effective ACLs.
 */
public final class RuntimeDatabaseRoles {
    public enum Role { METADATA, LOGICAL, PURGE_PREPARATION, PLACEMENT, BACKEND, GRANT_READ,
        READINESS, TRANSFER, WORKER, GOVERNANCE }
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
                }
            }
            return io.vyrnforge.filestorage.persistence.StorageMigrations.ready(source(Role.READINESS));
        }catch(SQLException|RuntimeException ex){return false;}
    }
    public void requireReady(){if(!ready())throw new IllegalStateException("separated database admission failed");}
}
