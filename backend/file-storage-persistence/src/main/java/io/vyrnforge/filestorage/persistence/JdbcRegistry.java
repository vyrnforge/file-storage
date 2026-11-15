package io.vyrnforge.filestorage.persistence;

import io.vyrnforge.filestorage.application.CredentialResolver;
import io.vyrnforge.filestorage.application.FileApplication.Code;
import io.vyrnforge.filestorage.application.FileApplication.Failure;
import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.platform.kernel.context.ActorReference;
import io.vyrnforge.platform.kernel.context.CorrelationContext;
import io.vyrnforge.platform.kernel.ids.UuidV7;
import io.vyrnforge.platform.kernel.audit.AuditIntentPort;
import io.vyrnforge.platform.kernel.event.EventIntentPort;
import io.vyrnforge.platform.kernel.persistence.jdbc.JacksonRecordCodec;
import io.vyrnforge.platform.kernel.persistence.jdbc.JdbcReliabilityTransaction;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Trusted internal registry commands and fresh database snapshots.
 * Administrative authorization, endpoint/network validation, actual secret
 * manager, and provider validation/activation belong to composition and FS-210B.
 * No network calls inside a metadata transaction.
 */
public final class JdbcRegistry {
    private final JdbcReliabilityTransaction transactions;
    private final Clock clock;

    public JdbcRegistry(DataSource source, Clock clock) {
        this.clock = Objects.requireNonNull(clock);
        transactions = new JdbcReliabilityTransaction(
                new KernelDataSource(Objects.requireNonNull(source), Connection.TRANSACTION_READ_COMMITTED),
                new JacksonRecordCodec(), clock);
    }

    /** Safe internal status projection, deliberately no target or credential reference. */
    public record RevisionView(BackendId backend, Provider provider, long revision,
                               BackendStatus status, boolean validated) {}
    public record LocationView(LocationId location, BackendId backend) {}

    public BackendId registerBackend(ActorReference actor, CorrelationContext correlation,
                                     Target target, CredentialRef credential) {
        Objects.requireNonNull(actor);
        Objects.requireNonNull(correlation);
        Target canonical = canonical(target);
        checkRef(credential);
        BackendId id = new BackendId(UuidV7.generate());
        return execute((c, events, audits) -> {
            update(c, """
                    INSERT INTO fs.backend (backend_id, provider, endpoint, region, addressing)
                    VALUES (?, ?, ?, ?, ?)
                    """, id.value(), canonical.provider().name(), canonical.endpoint().toString(),
                    canonical.region(), canonical.addressing().name());
            insertRevision(c, id, 1, credential);
            record(events, audits, actor, correlation, "file-storage.backend.registered",
                    "file-storage.backend", id.value(), Map.of("provider", canonical.provider().name(), "revision", 1));
            return id;
        });
    }

    /**
     * Initial location set only. A validated/active backend cannot silently
     * acquire another location that was not included in validation. Later
     * admission of locations requires explicit revision-aware validation.
     */
    public LocationId registerLocation(ActorReference actor, CorrelationContext correlation,
                                       BackendId backend, String bucket, String prefix) {
        Objects.requireNonNull(actor);
        Objects.requireNonNull(correlation);
        Objects.requireNonNull(backend);
        String normalized = namespace(bucket, prefix);
        LocationId id = new LocationId(UuidV7.generate());
        return execute((c, events, audits) -> {
            // Registry writes are infrequent. Serialize location admission across
            // backend aliases visible in this registry; never hold across I/O.
            try (var st = c.createStatement()) {
                st.execute("LOCK TABLE fs.storage_location IN SHARE ROW EXCLUSIVE MODE");
            }
            Target target = lockBackend(c, backend);
            try (var ps = statement(c, """
                    SELECT 1 FROM fs.backend_revision WHERE backend_id=?
                    AND (status<>'DRAFT' OR validated_revision IS NOT NULL)
                    """, backend.value()); var rs = ps.executeQuery()) {
                if (rs.next()) throw new Failure(Code.CONFLICT);
            }
            try (var ps = statement(c, """
                    SELECT b.endpoint, l.prefix FROM fs.storage_location l
                    JOIN fs.backend b ON b.backend_id=l.backend_id WHERE l.bucket=?
                    """, bucket); var rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (endpointIdentity(target.endpoint()).equals(endpointIdentity(URI.create(rs.getString(1))))) {
                        String existing = normalizedPrefix(rs.getString(2));
                        if (normalized.startsWith(existing) || existing.startsWith(normalized)) {
                            throw new Failure(Code.CONFLICT);
                        }
                    }
                }
            }
            update(c, """
                    INSERT INTO fs.storage_location (location_id, backend_id, bucket, prefix)
                    VALUES (?, ?, ?, ?)
                    """, id.value(), backend.value(), bucket, normalized);
            record(events, audits, actor, correlation, "file-storage.location.registered",
                    "file-storage.location", id.value(), Map.of("backendId", backend.value().toString()));
            return id;
        });
    }

    /** Append a new unvalidated candidate; expected is latest revision, not active pointer. */
    public long reviseCredential(ActorReference actor, CorrelationContext correlation,
                                 BackendId backend, long expected, CredentialRef next) {
        Objects.requireNonNull(actor);
        Objects.requireNonNull(correlation);
        Objects.requireNonNull(backend);
        checkRef(next);
        if (expected < 1 || expected == Long.MAX_VALUE) throw new Failure(Code.INVALID);
        return execute((c, events, audits) -> {
            lockBackend(c, backend);
            long latest;
            try (var ps = statement(c, """
                    SELECT config_revision, status FROM fs.backend_revision WHERE backend_id=?
                    ORDER BY config_revision DESC LIMIT 1
                    """, backend.value()); var rs = ps.executeQuery()) {
                if (!rs.next()) throw new Failure(Code.NOT_FOUND);
                latest = rs.getLong(1);
                if (latest != expected) throw new Failure(Code.CONFLICT);
                if (!List.of("ACTIVE", "DRAFT").contains(rs.getString(2))) throw new Failure(Code.INVALID);
            }
            insertRevision(c, backend, latest + 1, next);
            record(events, audits, actor, correlation, "file-storage.backend.credential-revised",
                    "file-storage.backend", backend.value(),
                    Map.of("previousRevision", latest, "revision", latest + 1));
            return latest + 1;
        });
    }

    /** Fresh committed state, never an in-memory active pointer cache. */
    public Optional<RevisionView> active(BackendId backend) {
        Objects.requireNonNull(backend);
        return execute((c, events, audits) -> {
            try (var ps = statement(c, """
                    SELECT b.provider, r.config_revision, r.status, r.validated_revision
                    FROM fs.backend_revision r JOIN fs.backend b USING (backend_id)
                    WHERE r.backend_id=? AND r.status='ACTIVE'
                    """, backend.value()); var rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(view(backend, rs)) : Optional.empty();
            }
        });
    }

    /** Descending immutable revision identities; before=0 is first page. */
    public List<RevisionView> history(BackendId backend, long before, int limit) {
        Objects.requireNonNull(backend);
        bound(limit);
        if (before < 0) throw new Failure(Code.INVALID);
        return execute((c, events, audits) -> {
            try (var ps = statement(c, """
                    SELECT b.provider, r.config_revision, r.status, r.validated_revision
                    FROM fs.backend_revision r JOIN fs.backend b USING (backend_id)
                    WHERE r.backend_id=? AND (?=0 OR r.config_revision<?)
                    ORDER BY r.config_revision DESC LIMIT ?
                    """, backend.value(), before, before, limit); var rs = ps.executeQuery()) {
                List<RevisionView> result = new ArrayList<>();
                while (rs.next()) result.add(view(backend, rs));
                return List.copyOf(result);
            }
        });
    }

    public List<LocationView> locations(BackendId backend, UUID after, int limit) {
        Objects.requireNonNull(backend);
        bound(limit);
        return execute((c, events, audits) -> {
            try (var ps = statement(c, """
                    SELECT location_id FROM fs.storage_location
                    WHERE backend_id=? AND (?::uuid IS NULL OR location_id>?)
                    ORDER BY location_id LIMIT ?
                    """, backend.value(), after, after, limit); var rs = ps.executeQuery()) {
                List<LocationView> result = new ArrayList<>();
                while (rs.next()) result.add(new LocationView(new LocationId(rs.getObject(1, UUID.class)), backend));
                return List.copyOf(result);
            }
        });
    }

    private record Pinned(Target target, Location location, CredentialRef credential) {}

    /**
     * Trusted adapter composition only. Resolve an already-authorized exact
     * existing-access pin, never the latest reference. No credential material
     * returned to consumers. External lookup happens after DB transaction ends.
     */
    public <C extends AutoCloseable> Resolved<C> resolveExisting(BackendId backend, long revision,
                                                                LocationId location, CredentialResolver<C> resolver) {
        Objects.requireNonNull(backend);
        Objects.requireNonNull(location);
        Objects.requireNonNull(resolver);
        if (revision < 1) throw new Failure(Code.INVALID);
        Pinned pin = execute((c, events, audits) -> {
            try (var ps = statement(c, """
                    SELECT b.provider, b.endpoint, b.region, b.addressing, l.bucket, l.prefix, r.credential_ref
                    FROM fs.backend b JOIN fs.backend_revision r USING (backend_id)
                    JOIN fs.storage_location l USING (backend_id)
                    WHERE b.backend_id=? AND r.config_revision=? AND l.location_id=?
                      AND r.status IN ('ACTIVE','DRAINING') AND r.validated_revision=r.config_revision
                    """, backend.value(), revision, location.value()); var rs = ps.executeQuery()) {
                if (!rs.next()) throw new Failure(Code.NOT_FOUND);
                return new Pinned(target(rs), new Location(location, backend, rs.getString(5), rs.getString(6)),
                        new CredentialRef(rs.getString(7)));
            }
        });
        try {
            C credential = Objects.requireNonNull(resolver.resolve(pin.credential()));
            return new Resolved<>(pin.target(), pin.location(), revision, credential);
        } catch (RuntimeException ex) {
            // No secret-store details, no alternate credential or revision.
            throw new Failure(Code.DENIED);
        }
    }

    public record Resolved<C extends AutoCloseable>(Target target, Location location, long revision,
                                                     C credential) implements AutoCloseable {
        @Override public String toString() { return "Resolved[redacted]"; }
        @Override public void close() {
            try { credential.close(); }
            catch (Exception ex) { throw new JdbcTransactions.PersistenceFailure(); }
        }
    }

    private static RevisionView view(BackendId backend, ResultSet rs) throws SQLException {
        return new RevisionView(backend, Provider.valueOf(rs.getString(1)), rs.getLong(2),
                BackendStatus.valueOf(rs.getString(3)), rs.getObject(4) != null);
    }

    private static Target lockBackend(Connection c, BackendId backend) throws SQLException {
        try (var ps = statement(c, """
                SELECT provider, endpoint, region, addressing FROM fs.backend WHERE backend_id=? FOR UPDATE
                """, backend.value()); var rs = ps.executeQuery()) {
            if (!rs.next()) throw new Failure(Code.NOT_FOUND);
            return target(rs);
        }
    }

    private static Target target(ResultSet rs) throws SQLException {
        return new Target(Provider.valueOf(rs.getString(1)), URI.create(rs.getString(2)),
                rs.getString(3), Addressing.valueOf(rs.getString(4)));
    }

    private static Target canonical(Target target) {
        Objects.requireNonNull(target);
        URI endpoint = target.endpoint();
        if (!(endpoint.getPath().isEmpty() || "/".equals(endpoint.getPath()))
                || endpoint.getPort() == 0 || endpoint.getPort() > 65535
                || !target.region().matches("[A-Za-z0-9-]{1,64}")) throw new Failure(Code.INVALID);
        return new Target(target.provider(), URI.create(endpointIdentity(endpoint)),
                target.region(), target.addressing());
    }

    private static String endpointIdentity(URI endpoint) {
        String host = endpoint.getHost().toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        int port = endpoint.getPort();
        return "https://" + host + (port == -1 || port == 443 ? "" : ":" + port);
    }

    private static String normalizedPrefix(String prefix) {
        return prefix.isEmpty() || prefix.endsWith("/") ? prefix : prefix + "/";
    }

    private static String namespace(String bucket, String prefix) {
        if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
                || prefix == null || prefix.startsWith("/") || prefix.contains("\\")
                || prefix.codePoints().anyMatch(Character::isISOControl)
                || prefix.contains("//") || List.of(prefix.split("/")).stream().anyMatch(s -> s.equals(".") || s.equals(".."))
                || normalizedPrefix(prefix).getBytes(StandardCharsets.UTF_8).length > 512) {
            throw new Failure(Code.INVALID);
        }
        return normalizedPrefix(prefix);
    }

    private static void checkRef(CredentialRef ref) {
        if (ref == null || ref.value().length() > 512) throw new Failure(Code.INVALID);
    }

    private static void bound(int limit) {
        if (limit < 1 || limit > 100) throw new Failure(Code.INVALID);
    }

    private static void insertRevision(Connection c, BackendId backend, long revision, CredentialRef credential)
            throws SQLException {
        update(c, """
                INSERT INTO fs.backend_revision (backend_id, config_revision, credential_ref, status, validated_revision)
                VALUES (?, ?, ?, 'DRAFT', NULL)
                """, backend.value(), revision, credential.value());
    }

    private void record(EventIntentPort events, AuditIntentPort audits, ActorReference actor,
                        CorrelationContext correlation, String type, String resource, UUID id, Map<String, Object> payload) {
        MutationIntents.record(events, audits, clock, correlation, actor, null, type, resource, id.toString(), payload);
    }

    private interface Work<T> {
        T run(Connection c, EventIntentPort events, AuditIntentPort audits) throws SQLException;
    }

    private <T> T execute(Work<T> work) {
        try {
            return transactions.execute((c, events, audits) -> {
                try { return work.run(c, events, audits); }
                catch (SQLException ex) { throw JdbcTransactions.translate(ex); }
            });
        } catch (Failure | JdbcTransactions.PersistenceFailure ex) { throw ex; }
        catch (RuntimeException ex) { throw new JdbcTransactions.PersistenceFailure(); }
    }

    private static PreparedStatement statement(Connection c, String sql, Object... values) throws SQLException {
        var ps = c.prepareStatement(sql);
        try {
            for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i]);
            return ps;
        } catch (SQLException ex) { ps.close(); throw ex; }
    }

    private static void update(Connection c, String sql, Object... values) throws SQLException {
        try (var ps = statement(c, sql, values)) {
            if (ps.executeUpdate() != 1) throw new Failure(Code.CONFLICT);
        }
    }
}
