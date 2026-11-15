package io.vyrnforge.filestorage.runtime;

import io.vyrnforge.filestorage.application.BackendValidator;
import io.vyrnforge.filestorage.application.FileApplication.Code;
import io.vyrnforge.filestorage.application.FileApplication.Failure;
import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.filestorage.persistence.JdbcConfiguration;
import io.vyrnforge.filestorage.persistence.JdbcRegistry;
import io.vyrnforge.filestorage.security.SecuredBackendAdmin.Authorized;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;

/** Trusted runtime composition. Validator and exact target policy are never HTTP inputs. */
public final class BackendAdministration {
    private final JdbcRegistry registry;
    private final JdbcConfiguration configuration;
    private final Set<Target> approvedTargets;
    private final BackendValidator validator;

    public BackendAdministration(DataSource source, Clock clock, Set<Target> approvedTargets, BackendValidator validator) {
        this.registry = new JdbcRegistry(source, clock);
        this.configuration = new JdbcConfiguration(source, clock);
        this.approvedTargets = Set.copyOf(approvedTargets);
        this.validator = Objects.requireNonNull(validator);
    }

    public JdbcRegistry registry() { return registry; }

    public BackendId register(Authorized context, Target target, CredentialRef reference) {
        // Admission uses exact canonical deployment targets; no network work or caller approval assertions.
        if (!approvedTargets.contains(target)) throw new Failure(Code.DENIED);
        return registry.registerBackend(context.actor(), context.correlation(), target, reference);
    }

    public long validate(Authorized context, BackendId backend, long revision) {
        return registry.validateDraft(context.actor(), context.correlation(), backend, revision, approvedTargets, validator);
    }

    public long activate(Authorized context, BackendId backend, long expected, long candidate) {
        return configuration.activateBackend(context.actor(), context.correlation(), backend, expected, candidate);
    }

    public long drain(Authorized context, BackendId backend, long revision) {
        return configuration.drainBackend(context.actor(), context.correlation(), backend, revision);
    }

    public long retire(Authorized context, BackendId backend, long revision) {
        return configuration.retireBackend(context.actor(), context.correlation(), backend, revision);
    }
}
