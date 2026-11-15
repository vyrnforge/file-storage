package io.vyrnforge.filestorage.persistence;

import io.vyrnforge.filestorage.application.FileApplication.Code;
import io.vyrnforge.filestorage.application.FileApplication.Failure;
import io.vyrnforge.filestorage.domain.StorageModel;
import io.vyrnforge.filestorage.domain.StorageModel.BackendId;
import io.vyrnforge.filestorage.domain.StorageModel.LocationId;
import io.vyrnforge.filestorage.domain.StorageModel.Scope;
import io.vyrnforge.platform.kernel.persistence.jdbc.JacksonRecordCodec;
import io.vyrnforge.platform.kernel.persistence.jdbc.JdbcReliabilityTransaction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Internal persistence commands, not an administrative authorization boundary.
 * Only a future authorized control-plane use case may expose these operations.
 * Validation evidence must have been persisted by a trusted validator; no network I/O here.
 */
public final class JdbcConfiguration {
    private final JdbcReliabilityTransaction transactions;

    public JdbcConfiguration(DataSource dataSource, Clock clock) {
        transactions = new JdbcReliabilityTransaction(
                new KernelDataSource(Objects.requireNonNull(dataSource), Connection.TRANSACTION_READ_COMMITTED),
                new JacksonRecordCodec(), Objects.requireNonNull(clock));
    }

    public record PolicyRule(LocationId location, int priority, boolean eligible) {
        public PolicyRule {
            Objects.requireNonNull(location);
            if (priority < 0) {
                throw new Failure(Code.INVALID);
            }
        }
    }

    /**
     * Zero means first activation only. Replacing an active credential revision
     * drains the previous revision but never rewrites a session's pinned identity.
     */
    public long activateBackend(BackendId backend, long expected, long candidate) {
        Objects.requireNonNull(backend);
        if (expected < 0 || candidate <= expected) {
            throw new Failure(Code.INVALID);
        }
        return execute(connection -> {
            try (var ps = statement(connection, "SELECT backend_id FROM fs.backend WHERE backend_id=? FOR UPDATE",
                    backend.value()); var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new Failure(Code.NOT_FOUND);
                }
            }
            long current = 0;
            long historical = 0;
            try (var ps = statement(connection, """
                    SELECT config_revision, status FROM fs.backend_revision
                    WHERE backend_id=? AND status <> 'DRAFT'
                    """, backend.value()); var rs = ps.executeQuery()) {
                while (rs.next()) {
                    long revision = rs.getLong(1);
                    historical = Math.max(historical, revision);
                    if ("ACTIVE".equals(rs.getString(2))) {
                        current = revision;
                    }
                }
            }
            if (current != expected || (current == 0 && historical != 0)
                    || candidate <= historical) {
                throw new Failure(Code.CONFLICT);
            }
            try (var ps = statement(connection, """
                    SELECT status, validated_revision FROM fs.backend_revision
                    WHERE backend_id=? AND config_revision=? FOR UPDATE
                    """, backend.value(), candidate); var rs = ps.executeQuery()) {
                if (!rs.next() || !"DRAFT".equals(rs.getString(1))
                        || !Long.valueOf(candidate).equals(rs.getObject(2, Long.class))) {
                    throw new Failure(Code.INVALID);
                }
            }
            if (current != 0) {
                requireOne(update(connection, """
                        UPDATE fs.backend_revision SET status='DRAINING'
                        WHERE backend_id=? AND config_revision=? AND status='ACTIVE'
                        """, backend.value(), current));
            }
            requireOne(update(connection, """
                    UPDATE fs.backend_revision SET status='ACTIVE'
                    WHERE backend_id=? AND config_revision=? AND status='DRAFT'
                      AND validated_revision=config_revision
                    """, backend.value(), candidate));
            return candidate;
        });
    }

    /**
     * Publish a new revision (expected + 1). Zero denotes initial publication.
     * Rollback of routing is a new revision with the historical rule content.
     * The caller supplies already-authorized eligible locations; this is not a grant API.
     */
    public long publishPolicy(Scope scope, long expected, List<PolicyRule> input) {
        Objects.requireNonNull(scope);
        List<PolicyRule> rules = List.copyOf(input);
        if (expected < 0 || expected == Long.MAX_VALUE || rules.isEmpty()
                || new HashSet<>(rules.stream().map(PolicyRule::location).toList()).size() != rules.size()) {
            throw new Failure(Code.INVALID);
        }
        long revision = expected + 1;
        return execute(connection -> {
            // Stable backend locks prevent concurrent activation from changing the
            // selected configuration during validation/publication.
            for (PolicyRule rule : rules.stream()
                    .sorted(Comparator.comparing(r -> r.location().value())).toList()) {
                try (var ps = statement(connection, """
                        SELECT b.backend_id FROM fs.storage_location l
                        JOIN fs.backend b ON b.backend_id=l.backend_id
                        WHERE l.location_id=? FOR SHARE OF b
                        """, rule.location().value()); var rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new Failure(Code.INVALID);
                    }
                }
            }
            var tenant = scope.tenant().value();
            long current = 0;
            try (var ps = statement(connection, """
                    SELECT policy_revision FROM fs.active_policy
                    WHERE tenant_id=? AND consumer=? AND purpose=? FOR UPDATE
                    """, tenant, scope.consumer(), scope.purpose()); var rs = ps.executeQuery()) {
                if (rs.next()) {
                    current = rs.getLong(1);
                }
            }
            if (current != expected) {
                throw new Failure(Code.CONFLICT);
            }
            update(connection, """
                    INSERT INTO fs.policy_revision (tenant_id, consumer, purpose, policy_revision)
                    VALUES (?, ?, ?, ?)
                    """, tenant, scope.consumer(), scope.purpose(), revision);
            for (PolicyRule rule : rules) {
                update(connection, """
                        INSERT INTO fs.policy_rule
                            (tenant_id, consumer, purpose, policy_revision, location_id, priority, eligible)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, tenant, scope.consumer(), scope.purpose(), revision,
                        rule.location().value(), rule.priority(), rule.eligible());
            }
            requireOne(update(connection, """
                    UPDATE fs.policy_revision SET sealed=true
                    WHERE tenant_id=? AND consumer=? AND purpose=? AND policy_revision=?
                    """, tenant, scope.consumer(), scope.purpose(), revision));
            if (expected == 0) {
                requireOne(update(connection, """
                        INSERT INTO fs.active_policy (tenant_id, consumer, purpose, policy_revision)
                        VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
                        """, tenant, scope.consumer(), scope.purpose(), revision));
            } else {
                requireOne(update(connection, """
                        UPDATE fs.active_policy SET policy_revision=?
                        WHERE tenant_id=? AND consumer=? AND purpose=? AND policy_revision=?
                        """, revision, tenant, scope.consumer(), scope.purpose(), expected));
            }
            // Reuse the actual placement algorithm. No fallback to another rule.
            // If invalid, the Kernel transaction rolls back the pointer and all new rows.
            JdbcTransactions.onConnection(connection, unit -> {
                var config = unit.configuration(scope).orElseThrow(() -> new Failure(Code.NO_PLACEMENT));
                try {
                    StorageModel.select(scope, config.policy(), config.locations(), config.backends(), config.eligible());
                } catch (IllegalStateException ex) {
                    throw new Failure(Code.NO_PLACEMENT);
                }
                return null;
            });
            return revision;
        });
    }

    private interface Work {
        long run(Connection connection) throws SQLException;
    }

    private long execute(Work work) {
        try {
            return transactions.execute((connection, events, audits) -> {
                try {
                    return work.run(connection);
                } catch (SQLException ex) {
                    throw JdbcTransactions.translate(ex);
                }
            });
        } catch (Failure | JdbcTransactions.PersistenceFailure ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new JdbcTransactions.PersistenceFailure();
        }
    }

    private static PreparedStatement statement(Connection connection, String sql, Object... values)
            throws SQLException {
        var ps = connection.prepareStatement(sql);
        try {
            for (int i = 0; i < values.length; i++) {
                ps.setObject(i + 1, values[i]);
            }
            return ps;
        } catch (SQLException ex) {
            ps.close();
            throw ex;
        }
    }

    private static int update(Connection connection, String sql, Object... values) throws SQLException {
        try (var ps = statement(connection, sql, values)) {
            return ps.executeUpdate();
        }
    }

    private static void requireOne(int count) {
        if (count != 1) {
            throw new Failure(Code.CONFLICT);
        }
    }
}
