package io.vyrnforge.filestorage.persistence;

import io.vyrnforge.filestorage.application.FileApplication.Code;
import io.vyrnforge.filestorage.application.FileApplication.Failure;
import io.vyrnforge.filestorage.application.PolicyAuthority;
import io.vyrnforge.filestorage.domain.StorageModel.LocationId;
import io.vyrnforge.filestorage.domain.StorageModel.Scope;
import io.vyrnforge.platform.kernel.context.ActorReference;
import io.vyrnforge.platform.kernel.context.CorrelationContext;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Internal application-facing control plane over the existing trusted
 * configuration CAS. The injected authority must be bound to verified identity
 * and server-side entitlement policy. No public routes or provider I/O.
 */
public final class JdbcPolicyControl {
    private static final int MAX_RULES = 100;
    private final DataSource source;
    private final JdbcConfiguration configuration;
    private final PolicyAuthority authority;

    public JdbcPolicyControl(DataSource source, Clock clock, PolicyAuthority authority) {
        this.source = Objects.requireNonNull(source);
        this.configuration = new JdbcConfiguration(source, clock);
        this.authority = Objects.requireNonNull(authority);
    }

    /** Logical-only projection; never contains credentials, endpoints or object keys. */
    public record PolicyView(Scope scope, long revision, List<JdbcConfiguration.PolicyRule> rules) {
        public PolicyView { rules = List.copyOf(rules); }
    }

    public long publish(ActorReference actor, CorrelationContext correlation, Scope scope,
                        long expected, List<JdbcConfiguration.PolicyRule> input) {
        authorize(actor, scope, Set.of());
        List<JdbcConfiguration.PolicyRule> rules = boundedRules(input);
        authorize(actor, scope, locationIds(rules));
        return configuration.publishPolicy(actor, correlation, scope, expected, rules);
    }

    /** Rollback copies immutable historical content into a new revision; no pointer rewind. */
    public long rollback(ActorReference actor, CorrelationContext correlation, Scope scope,
                         long expected, long historical) {
        authorize(actor, scope, Set.of());
        if (historical < 1 || expected <= historical) throw new Failure(Code.INVALID);
        var view = read(scope, historical).orElseThrow(() -> new Failure(Code.NOT_FOUND));
        var rules = boundedRules(view.rules());
        authorize(actor, scope, locationIds(rules));
        return configuration.publishPolicy(actor, correlation, scope, expected, rules, historical);
    }

    public Optional<PolicyView> active(ActorReference actor, Scope scope) {
        return authorizedRead(actor, scope, 0);
    }

    public Optional<PolicyView> revision(ActorReference actor, Scope scope, long revision) {
        if (revision < 1) throw new Failure(Code.INVALID);
        return authorizedRead(actor, scope, revision);
    }

    /** Bounded descending keyset page of sealed revision numbers, zero for first page. */
    public List<Long> history(ActorReference actor, Scope scope, long before, int limit) {
        authorize(actor, scope, Set.of());
        if (before < 0 || limit < 1 || limit > 100) throw new Failure(Code.INVALID);
        try (var c = source.getConnection(); var ps = c.prepareStatement("""
                SELECT policy_revision FROM fs.policy_revision
                WHERE tenant_id=? AND consumer=? AND purpose=? AND sealed
                  AND (?=0 OR policy_revision<?)
                ORDER BY policy_revision DESC LIMIT ?
                """)) {
            ps.setObject(1, scope.tenant().value());
            ps.setString(2, scope.consumer());
            ps.setString(3, scope.purpose());
            ps.setLong(4, before);
            ps.setLong(5, before);
            ps.setInt(6, limit);
            try (var rs = ps.executeQuery()) {
                List<Long> result = new ArrayList<>();
                while (rs.next()) result.add(rs.getLong(1));
                return List.copyOf(result);
            }
        } catch (SQLException ex) {
            throw JdbcTransactions.translate(ex);
        }
    }

    private Optional<PolicyView> authorizedRead(ActorReference actor, Scope scope, long revision) {
        authorize(actor, scope, Set.of());
        var result = read(scope, revision);
        result.ifPresent(view -> authorize(actor, scope, locationIds(view.rules())));
        return result;
    }

    /** One committed SQL snapshot: active pointer and immutable rules cannot be mixed. */
    private Optional<PolicyView> read(Scope scope, long revision) {
        try (var c = source.getConnection(); var ps = c.prepareStatement("""
                SELECT r.policy_revision, p.location_id, p.priority, p.eligible
                FROM fs.policy_revision r
                JOIN fs.policy_rule p USING (tenant_id, consumer, purpose, policy_revision)
                LEFT JOIN fs.active_policy a USING (tenant_id, consumer, purpose)
                WHERE r.tenant_id=? AND r.consumer=? AND r.purpose=? AND r.sealed
                  AND ((?=0 AND a.policy_revision=r.policy_revision) OR r.policy_revision=?)
                ORDER BY p.priority, p.location_id LIMIT 101
                """)) {
            ps.setObject(1, scope.tenant().value());
            ps.setString(2, scope.consumer());
            ps.setString(3, scope.purpose());
            ps.setLong(4, revision);
            ps.setLong(5, revision);
            try (var rs = ps.executeQuery()) {
                List<JdbcConfiguration.PolicyRule> rules = new ArrayList<>();
                long found = 0;
                while (rs.next()) {
                    found = rs.getLong(1);
                    rules.add(new JdbcConfiguration.PolicyRule(
                            new LocationId(rs.getObject(2, UUID.class)), rs.getInt(3), rs.getBoolean(4)));
                }
                if (rules.size() > MAX_RULES) throw new Failure(Code.INVALID);
                return found == 0 ? Optional.empty() : Optional.of(new PolicyView(scope, found, rules));
            }
        } catch (SQLException ex) {
            throw JdbcTransactions.translate(ex);
        }
    }

    private void authorize(ActorReference actor, Scope scope, Set<LocationId> locations) {
        Objects.requireNonNull(actor);
        Objects.requireNonNull(scope);
        if (scope.consumer().length() > 128 || scope.purpose().length() > 128) throw new Failure(Code.INVALID);
        boolean allowed;
        try { allowed = authority.permits(actor, scope, locations); }
        catch (RuntimeException ex) { throw new Failure(Code.DENIED); }
        if (!allowed) throw new Failure(Code.DENIED);
    }

    private static List<JdbcConfiguration.PolicyRule> boundedRules(List<JdbcConfiguration.PolicyRule> input) {
        if (input == null || input.isEmpty() || input.size() > MAX_RULES
                || input.stream().anyMatch(Objects::isNull)) throw new Failure(Code.INVALID);
        var rules = List.copyOf(input);
        if (locationIds(rules).size() != rules.size()) throw new Failure(Code.INVALID);
        return rules;
    }

    private static Set<LocationId> locationIds(List<JdbcConfiguration.PolicyRule> rules) {
        Set<LocationId> ids = new HashSet<>();
        rules.forEach(rule -> ids.add(rule.location()));
        return Set.copyOf(ids);
    }
}
