package io.vyrnforge.filestorage.runtime;

import java.io.PrintWriter;
import java.sql.*;
import java.util.Properties;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** Fresh bounded TLS-verified connections; only configured login and mounted secret. */
final class StagingRoleDataSource implements DataSource {
    private final StagingBootstrapConfig config;
    private final StagingBootstrapConfig.DbRole role;
    private final MountedStagingSecrets secrets;
    StagingRoleDataSource(StagingBootstrapConfig config,StagingBootstrapConfig.DbRole role,MountedStagingSecrets secrets){
        this.config=config;this.role=role;this.secrets=secrets;
    }
    @Override public Connection getConnection()throws SQLException{
        try{
            var p=new Properties();p.setProperty("user",role.login());p.setProperty("password",secrets.databasePassword(role.reference()));
            p.setProperty("sslmode","verify-full");p.setProperty("sslrootcert",config.ca.toString());
            p.setProperty("connectTimeout",Integer.toString(config.connectSeconds));p.setProperty("socketTimeout",Integer.toString(config.socketSeconds));
            p.setProperty("loginTimeout",Integer.toString(config.connectSeconds));
            p.setProperty("gssEncMode","disable");p.setProperty("ApplicationName","file-storage-staging");
            return DriverManager.getConnection("jdbc:postgresql://"+config.host+":"+config.dbPort+"/"+config.database,p);
        }catch(SQLException|RuntimeException ex){throw new SQLException("staging database unavailable","08001");}
    }
    @Override public Connection getConnection(String user,String password)throws SQLException{throw new SQLFeatureNotSupportedException("explicit role required");}
    @Override public PrintWriter getLogWriter(){return null;}
    @Override public void setLogWriter(PrintWriter writer)throws SQLException{throw new SQLFeatureNotSupportedException();}
    @Override public void setLoginTimeout(int seconds)throws SQLException{throw new SQLFeatureNotSupportedException();}
    @Override public int getLoginTimeout(){return config.connectSeconds;}
    @Override public Logger getParentLogger(){return Logger.getLogger("file-storage");}
    @Override public <T>T unwrap(Class<T> type)throws SQLException{if(type.isInstance(this))return type.cast(this);throw new SQLException("unsupported unwrap");}
    @Override public boolean isWrapperFor(Class<?> type){return type.isInstance(this);}
    @Override public String toString(){return "StagingRoleDataSource[redacted]";}
}
