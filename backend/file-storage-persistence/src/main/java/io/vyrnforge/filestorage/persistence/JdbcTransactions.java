package io.vyrnforge.filestorage.persistence;

import io.vyrnforge.filestorage.application.FileApplication.*;
import io.vyrnforge.filestorage.domain.StorageModel;
import io.vyrnforge.filestorage.domain.StorageModel.*;
import java.net.URI;
import java.sql.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;

/**
 * Persistence for the initial create/read aggregate only. No external side effects,
 * retries, live provider access, or Kernel reliability lease handling in this slice.
 */
public final class JdbcTransactions implements Transactions {
    private final DataSource dataSource;

    public JdbcTransactions(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    /** Neutral database failure. Do not expose SQL, connection details or row data. */
    public static final class PersistenceFailure extends RuntimeException {
        public PersistenceFailure() {
            super("PERSISTENCE_UNAVAILABLE");
        }
    }

    private static RuntimeException translate(SQLException ex) {
        String state = ex.getSQLState();
        if ("23505".equals(state) || "40001".equals(state) || "40P01".equals(state)) {
            return new Failure(Code.CONFLICT);
        }
        if (state != null && (state.startsWith("22") || "23514".equals(state)
                || "23503".equals(state) || "23502".equals(state))) {
            return new Failure(Code.INVALID);
        }
        return new PersistenceFailure();
    }

    @Override
    public <T> T execute(Function<Unit, T> work) {
        Objects.requireNonNull(work);
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            JdbcUnit unit = new JdbcUnit(connection);
            try {
                T result = work.apply(unit);
                if (unit.failed) {
                    throw new PersistenceFailure();
                }
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException | Error ex) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    // No auto-commit reset after uncertain rollback. Close the connection.
                    // Never attach a raw SQL exception to a consumer-facing failure.
                }
                if (ex instanceof SQLException sql) {
                    throw translate(sql);
                }
                if (ex instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw (Error) ex;
            } finally {
                unit.active = false;
            }
        } catch (SQLException ex) {
            throw translate(ex);
        }
    }

    private static final class JdbcUnit implements Unit {
        private final Connection connection;
        private boolean active = true;
        private boolean failed;

        private JdbcUnit(Connection connection) {
            this.connection = connection;
        }

        private void requireActive() {
            if (!active || failed) {
                throw new PersistenceFailure();
            }
        }

        private PreparedStatement statement(String sql, Object... parameters) throws SQLException {
            requireActive();
            PreparedStatement ps = connection.prepareStatement(sql);
            try {
                for (int i = 0; i < parameters.length; i++) {
                    ps.setObject(i + 1, parameters[i]);
                }
                return ps;
            } catch (SQLException ex) {
                ps.close();
                throw ex;
            }
        }

        private RuntimeException failure(SQLException ex) {
            failed = true;
            return translate(ex);
        }

        @Override
        public Optional<Configuration> configuration(Scope scope) {
            Objects.requireNonNull(scope);
            String sql = """
                    SELECT a.policy_revision, r.priority, r.eligible,
                           l.location_id, l.backend_id, l.bucket, l.prefix,
                           b.provider, b.endpoint, b.region, b.addressing,
                           v.config_revision, v.credential_ref, v.validated_revision
                    FROM fs.active_policy a
                    LEFT JOIN fs.policy_rule r USING (tenant_id, consumer, purpose, policy_revision)
                    LEFT JOIN fs.storage_location l ON l.location_id = r.location_id
                    LEFT JOIN fs.backend b ON b.backend_id = l.backend_id
                    LEFT JOIN fs.backend_revision v
                        ON v.backend_id = b.backend_id AND v.status = 'ACTIVE'
                    WHERE a.tenant_id = ? AND a.consumer = ? AND a.purpose = ?
                    ORDER BY r.priority, l.location_id
                    """;
            try (var ps = statement(sql, scope.tenant().value(), scope.consumer(), scope.purpose());
                 var rs = ps.executeQuery()) {
                long revision = 0;
                List<Rule> rules = new ArrayList<>();
                Map<LocationId, Location> locations = new HashMap<>();
                Map<BackendId, Backend> backends = new HashMap<>();
                Set<LocationId> eligible = new HashSet<>();
                while (rs.next()) {
                    revision = rs.getLong("policy_revision");
                    UUID rawLocation = rs.getObject("location_id", UUID.class);
                    if (rawLocation == null) {
                        continue;
                    }
                    LocationId location = new LocationId(rawLocation);
                    BackendId backend = new BackendId(rs.getObject("backend_id", UUID.class));
                    locations.put(location, new Location(location, backend,
                            rs.getString("bucket"), rs.getString("prefix")));
                    rules.add(new Rule(scope, location, rs.getInt("priority")));
                    if (rs.getBoolean("eligible")) {
                        eligible.add(location);
                    }
                    Long configRevision = rs.getObject("config_revision", Long.class);
                    if (configRevision != null) {
                        Target target = new Target(Provider.valueOf(rs.getString("provider")),
                                URI.create(rs.getString("endpoint")), rs.getString("region"),
                                Addressing.valueOf(rs.getString("addressing")));
                        backends.put(backend, new Backend(backend, target,
                                new CredentialRef(rs.getString("credential_ref")), configRevision,
                                BackendStatus.ACTIVE, rs.getObject("validated_revision", Long.class)));
                    }
                }
                return revision == 0 ? Optional.empty()
                        : Optional.of(new Configuration(new Policy(revision, rules), locations, backends, eligible));
            } catch (SQLException ex) {
                throw failure(ex);
            } catch (IllegalArgumentException ex) {
                failed = true;
                throw new PersistenceFailure();
            }
        }

        private static final String ENTRY = """
                SELECT f.file_id, f.tenant_id, f.name, f.state AS file_state,
                       v.version_id, v.location_id, v.object_key, v.state AS version_state,
                       s.session_id, s.policy_revision, s.config_revision, s.consumer, s.purpose,
                       s.expires_at, s.expires_nano_remainder, s.state AS session_state,
                       r.actor, r.request_key, r.expected_size, r.declared_mime_type
                FROM fs.file f
                JOIN fs.file_version v ON v.tenant_id = f.tenant_id AND v.file_id = f.file_id
                JOIN fs.upload_session s ON s.tenant_id = v.tenant_id AND s.file_id = v.file_id
                    AND s.version_id = v.version_id
                JOIN fs.initiation_request r ON r.tenant_id = s.tenant_id AND r.session_id = s.session_id
                    AND r.consumer = s.consumer
                """;

        @Override
        public Optional<Entry> byRequest(RequestIdentity identity) {
            Caller caller = identity.caller();
            return find(ENTRY + """
                    WHERE f.tenant_id = ? AND r.actor = ? AND r.consumer = ? AND r.request_key = ?
                    """, caller.tenant().value(), caller.actor(), caller.consumer(), identity.key());
        }

        @Override
        public Optional<Entry> byFile(TenantId tenant, FileId file) {
            return find(ENTRY + "WHERE f.tenant_id = ? AND f.file_id = ?",
                    tenant.value(), file.value());
        }

        @Override
        public Optional<Entry> bySession(TenantId tenant, SessionId session) {
            return find(ENTRY + "WHERE f.tenant_id = ? AND s.session_id = ?",
                    tenant.value(), session.value());
        }

        private Optional<Entry> find(String sql, Object... parameters) {
            try (var ps = statement(sql, parameters); var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Entry entry = readEntry(rs);
                if (rs.next()) {
                    failed = true;
                    throw new PersistenceFailure();
                }
                return Optional.of(entry);
            } catch (SQLException ex) {
                throw failure(ex);
            } catch (IllegalArgumentException ex) {
                failed = true;
                throw new PersistenceFailure();
            }
        }

        private static Entry readEntry(ResultSet rs) throws SQLException {
            if (!"ACTIVE".equals(rs.getString("file_state"))
                    || !"PENDING".equals(rs.getString("version_state"))
                    || !"INITIATED".equals(rs.getString("session_state"))) {
                throw new PersistenceFailure();
            }
            TenantId tenant = new TenantId(rs.getObject("tenant_id", UUID.class));
            File file = File.create(new FileId(rs.getString("file_id")), tenant, rs.getString("name"));
            VersionId versionId = new VersionId(rs.getObject("version_id", UUID.class));
            LocationId location = new LocationId(rs.getObject("location_id", UUID.class));
            Locator locator = new Locator(location, rs.getString("object_key"), null);
            Version version = Version.pending(file, versionId, locator);
            Scope scope = new Scope(tenant, rs.getString("consumer"), rs.getString("purpose"));
            Placement placement = new Placement(scope, location, rs.getLong("policy_revision"),
                    rs.getLong("config_revision"));
            Instant expiry = rs.getObject("expires_at", OffsetDateTime.class).toInstant()
                    .plusNanos(rs.getInt("expires_nano_remainder"));
            // Rehydrate original initiated state, not an implicit expiry transition.
            UploadSession session = UploadSession.restoreInitiated(
                    new SessionId(rs.getObject("session_id", UUID.class)), file, versionId,
                    scope, placement, locator, expiry);
            Caller caller = new Caller(tenant, rs.getString("actor"), scope.consumer());
            String key = rs.getString("request_key");
            Initiate command = new Initiate(scope.purpose(), file.name(), rs.getLong("expected_size"),
                    rs.getString("declared_mime_type"), key);
            return new Entry(file, version, session, new RequestIdentity(caller, key), command);
        }

        @Override
        public void insert(Entry entry) {
            requireActive();
            Objects.requireNonNull(entry);
            File file = entry.file();
            Version version = entry.version();
            UploadSession session = entry.session();
            Initiate command = entry.command();
            Scope scope = session.scope();
            if (file.state() != FileState.ACTIVE || version.state() != VersionState.PENDING
                    || session.state() != UploadState.INITIATED || version.locator().providerVersion() != null
                    || !file.name().equals(command.name())) {
                throw new Failure(Code.INVALID);
            }
            Configuration config = configuration(scope).orElseThrow(() -> new Failure(Code.NO_PLACEMENT));
            Placement selected;
            try {
                selected = StorageModel.select(scope, config.policy(), config.locations(),
                        config.backends(), config.eligible());
            } catch (IllegalStateException ex) {
                throw new Failure(Code.NO_PLACEMENT);
            }
            if (!selected.equals(session.placement())) {
                throw new Failure(Code.CONFLICT);
            }
            BackendId backend = config.locations().get(selected.location()).backend();
            UUID tenant = file.tenant().value();
            UUID location = selected.location().value();
            String fileId = file.id().value();
            UUID versionId = version.id().value();
            UUID sessionId = session.id().value();
            Instant expiry = session.expires();
            OffsetDateTime micros = expiry.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
            try {
                update("INSERT INTO fs.file (file_id, tenant_id, name, state) VALUES (?, ?, ?, 'ACTIVE')",
                        fileId, tenant, file.name());
                update("""
                        INSERT INTO fs.file_version (version_id, tenant_id, file_id, location_id, object_key, state)
                        VALUES (?, ?, ?, ?, ?, 'PENDING')
                        """, versionId, tenant, fileId, location, version.locator().objectKey());
                update("""
                        INSERT INTO fs.upload_session (session_id, tenant_id, file_id, version_id, location_id,
                            backend_id, config_revision, policy_revision, consumer, purpose,
                            expires_at, expires_nano_remainder, state)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'INITIATED')
                        """, sessionId, tenant, fileId, versionId, location, backend.value(),
                        selected.configRevision(), selected.policyRevision(), scope.consumer(), scope.purpose(),
                        micros, expiry.getNano() % 1000);
                update("""
                        INSERT INTO fs.initiation_request (tenant_id, actor, consumer, request_key,
                            session_id, expected_size, declared_mime_type)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, tenant, entry.request().caller().actor(), scope.consumer(), entry.request().key(),
                        sessionId, command.expectedSize(), command.mimeType());
            } catch (SQLException ex) {
                throw failure(ex);
            }
        }

        private void update(String sql, Object... parameters) throws SQLException {
            try (var ps = statement(sql, parameters)) {
                if (ps.executeUpdate() != 1) {
                    failed = true;
                    throw new PersistenceFailure();
                }
            }
        }
    }
}
