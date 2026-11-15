package io.vyrnforge.filestorage.persistence;

import io.vyrnforge.filestorage.application.FileApplication.Action;
import io.vyrnforge.filestorage.application.FileApplication.Caller;
import io.vyrnforge.filestorage.domain.StorageModel.FileId;
import io.vyrnforge.filestorage.domain.StorageModel.TenantId;
import io.vyrnforge.filestorage.security.SecuredFiles.Grant;
import io.vyrnforge.filestorage.security.SecuredFiles.GrantScope;
import io.vyrnforge.filestorage.security.SecuredFiles.Grants;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Trusted, read-only grant authority adapter. No grant issuance or cache.
 * Each call observes a fresh committed PostgreSQL snapshot, independently of
 * the metadata transaction. The security facade still checks action, purpose,
 * ownership and exact file identity against the authoritative resource.
 */
public final class JdbcGrants implements Grants {
    private final DataSource dataSource;
    private final Clock clock;
    private final int maxGrants;
    private final int queryTimeoutSeconds;

    public JdbcGrants(DataSource dataSource, Clock clock, int maxGrants, int queryTimeoutSeconds) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.clock = Objects.requireNonNull(clock);
        if (maxGrants < 1 || maxGrants == Integer.MAX_VALUE || queryTimeoutSeconds < 1) {
            throw new IllegalArgumentException("positive grant query limits required");
        }
        this.maxGrants = maxGrants;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    /** Neutral failure; never include connection details, SQL or grant contents. */
    public static final class GrantSourceUnavailable extends RuntimeException {
        private GrantSourceUnavailable() {
            super("GRANT_SOURCE_UNAVAILABLE");
        }
    }

    @Override
    public List<Grant> current(Caller caller) {
        Objects.requireNonNull(caller);
        // PostgreSQL timestamps have microsecond precision. Flooring the
        // instant preserves comparisons to stored microsecond boundaries.
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
        String sql = """
                SELECT tenant_id, actor, consumer, action, purpose, scope, file_id,
                       not_before, expires_at, revoked
                FROM fs.file_grant
                WHERE tenant_id=? AND actor=? AND consumer=?
                  AND NOT revoked AND not_before<=? AND expires_at>?
                ORDER BY grant_id
                LIMIT ?
                """;
        try (var connection = dataSource.getConnection()) {
            if (!connection.getAutoCommit()) {
                throw new GrantSourceUnavailable();
            }
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setReadOnly(true);
            try (var ps = connection.prepareStatement(sql)) {
                ps.setQueryTimeout(queryTimeoutSeconds);
                ps.setObject(1, caller.tenant().value());
                ps.setString(2, caller.actor());
                ps.setString(3, caller.consumer());
                ps.setObject(4, now);
                ps.setObject(5, now);
                ps.setInt(6, maxGrants + 1);
                try (var rs = ps.executeQuery()) {
                    List<Grant> grants = new ArrayList<>();
                    while (rs.next()) {
                        // Do not authorize from a truncated set or a malformed row.
                        if (grants.size() == maxGrants) {
                            throw new GrantSourceUnavailable();
                        }
                        String file = rs.getString("file_id");
                        Grant grant = new Grant(
                                new TenantId(rs.getObject("tenant_id", UUID.class)),
                                rs.getString("actor"), rs.getString("consumer"),
                                Action.valueOf(rs.getString("action")), rs.getString("purpose"),
                                GrantScope.valueOf(rs.getString("scope")),
                                file == null ? null : new FileId(file),
                                rs.getObject("not_before", OffsetDateTime.class).toInstant(),
                                rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                                rs.getBoolean("revoked"));
                        if (!caller.tenant().equals(grant.tenant()) || !caller.actor().equals(grant.actor())
                                || !caller.consumer().equals(grant.consumer())) {
                            throw new GrantSourceUnavailable();
                        }
                        grants.add(grant);
                    }
                    return List.copyOf(grants);
                }
            }
        } catch (SQLException | RuntimeException ex) {
            throw new GrantSourceUnavailable();
        }
    }
}
