package io.vyrnforge.filestorage.persistence;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** Kernel uses unqualified reliability tables; service tables are fs-qualified. */
final class KernelDataSource implements DataSource {
    private final DataSource delegate;
    private final int isolation;

    KernelDataSource(DataSource delegate, int isolation) {
        this.delegate = Objects.requireNonNull(delegate);
        this.isolation = isolation;
    }

    private Connection configure(Connection connection) throws SQLException {
        try {
            if (!connection.getAutoCommit()) {
                throw new SQLException("expected fresh auto-commit connection");
            }
            connection.setSchema("fs_kernel");
            connection.setTransactionIsolation(isolation);
            return connection;
        } catch (SQLException | RuntimeException ex) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                ex.addSuppressed(closeFailure);
            }
            throw ex;
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        return configure(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return configure(delegate.getConnection(username, password));
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter writer) throws SQLException {
        delegate.setLogWriter(writer);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        if (type.isInstance(this)) {
            return type.cast(this);
        }
        throw new SQLException("unsupported unwrap");
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
        return type.isInstance(this);
    }
}
