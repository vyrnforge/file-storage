package io.vyrnforge.filestorage.runtime.transfer;

import io.vyrnforge.filestorage.spi.StorageProvider;

/** Trusted adapter composition resolves only the instruction's exact persisted pin.
 * No provider URL, caller-supplied target, latest-revision fallback or arbitrary redirect.
 * Implementations must close acquired credential/client leases on all paths.
 */
@FunctionalInterface
public interface TransferProviderResolver {
    Lease resolve(TransferInstruction instruction);

    interface Lease extends AutoCloseable {
        StorageProvider provider();
        @Override void close();
    }
}
