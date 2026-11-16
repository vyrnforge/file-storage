package io.vyrnforge.filestorage.runtime.transfer;

import io.vyrnforge.filestorage.application.CredentialResolver;
import io.vyrnforge.filestorage.domain.StorageModel.Location;
import io.vyrnforge.filestorage.domain.StorageModel.Provider;
import io.vyrnforge.filestorage.domain.StorageModel.Target;
import io.vyrnforge.filestorage.persistence.JdbcRegistry;
import io.vyrnforge.filestorage.spi.StorageProvider;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Resolve only the authorized existing pin. No provider SDK or network fallback in runtime. */
public final class RegistryTransferProviderResolver<C extends AutoCloseable> implements TransferProviderResolver {
    @FunctionalInterface
    public interface AdapterFactory<C extends AutoCloseable> {
        /** Trusted deployment factory must bind exactly this target/location/credential.
         * The credential remains owned by the resolver; factory owns partial construction cleanup.
         */
        Lease open(Target target, Location location, long revision, C credential);
    }

    private final JdbcRegistry registry;
    private final CredentialResolver<C> credentials;
    private final Set<Target> approvedTargets;
    private final Map<Provider, AdapterFactory<C>> factories;
    private final JdbcRegistry.PinAdmission admission;

    public RegistryTransferProviderResolver(JdbcRegistry registry, CredentialResolver<C> credentials,
            Set<Target> approvedTargets, Map<Provider, AdapterFactory<C>> factories) {
        this(registry, credentials, approvedTargets, factories, (target, location, reference) -> true);
    }

    public RegistryTransferProviderResolver(JdbcRegistry registry, CredentialResolver<C> credentials,
            Set<Target> approvedTargets, Map<Provider, AdapterFactory<C>> factories,
            JdbcRegistry.PinAdmission admission) {
        this.registry = Objects.requireNonNull(registry);
        this.credentials = Objects.requireNonNull(credentials);
        this.approvedTargets = Set.copyOf(approvedTargets);
        this.factories = Map.copyOf(factories);
        this.admission = Objects.requireNonNull(admission);
    }

    @Override public Lease resolve(TransferInstruction instruction) {
        Objects.requireNonNull(instruction);
        JdbcRegistry.Resolved<C> resolved;
        try {
            resolved = registry.resolveExistingAdmitted(instruction.backend(), instruction.revision(),
                    instruction.locator().location(), (target, location, reference) -> approvedTargets.contains(target)
                            && factories.containsKey(target.provider())
                            && admission.allows(target, location, reference), credentials);
        } catch (RuntimeException ex) {
            throw new TransferFailure(TransferFailure.Code.UNAVAILABLE);
        }
        Lease adapter = null;
        try {
            var location = resolved.location();
            // Defense in depth: a locator must remain within the authorized persisted location.
            if (!location.backend().equals(instruction.backend()) || !location.id().equals(instruction.locator().location())
                    || resolved.revision() != instruction.revision()
                    || !within(location.prefix(), instruction.locator().objectKey()))
                throw new TransferFailure(TransferFailure.Code.DENIED);
            adapter = Objects.requireNonNull(factories.get(resolved.target().provider())
                    .open(resolved.target(), location, resolved.revision(), resolved.credential()));
            StorageProvider provider = Objects.requireNonNull(adapter.provider());
            return new OwnedLease(adapter, resolved, provider);
        } catch (RuntimeException ex) {
            try { if (adapter != null) adapter.close(); }
            catch (RuntimeException ignored) { /* neutral failure */ }
            finally { try { resolved.close(); } catch (RuntimeException ignored) { /* neutral failure */ } }
            throw new TransferFailure(TransferFailure.Code.UNAVAILABLE);
        }
    }

    private static boolean within(String prefix, String key) {
        String root = prefix.isEmpty() || prefix.endsWith("/") ? prefix : prefix + "/";
        return key.startsWith(root) && key.length() > root.length();
    }

    private static final class OwnedLease implements Lease {
        private final Lease adapter;
        private final AutoCloseable credential;
        private final StorageProvider provider;
        private final AtomicBoolean closed = new AtomicBoolean();

        private OwnedLease(Lease adapter, AutoCloseable credential, StorageProvider provider) {
            this.adapter = adapter; this.credential = credential; this.provider = provider;
        }
        @Override public StorageProvider provider() {
            if (closed.get()) throw new TransferFailure(TransferFailure.Code.UNAVAILABLE);
            return provider;
        }
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            boolean failed = false;
            try { adapter.close(); } catch (RuntimeException ex) { failed = true; }
            try { credential.close(); } catch (Exception ex) { failed = true; }
            if (failed) throw new TransferFailure(TransferFailure.Code.UNAVAILABLE);
        }
    }
}
