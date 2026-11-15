package io.vyrnforge.filestorage.runtime.transfer;

import java.time.Duration;
import java.util.Objects;

/** Explicit engineering profile; no production defaults. Adapter spool limits must also be configured. */
public record TransferLimits(long maxBytes, int concurrency, int bufferBytes, Duration timeout) {
    public TransferLimits {
        Objects.requireNonNull(timeout);
        if (maxBytes < 0 || concurrency < 1 || concurrency > 1024
                || bufferBytes < 1024 || bufferBytes > 65536 || timeout.isZero()
                || timeout.isNegative() || timeout.compareTo(Duration.ofHours(1)) > 0)
            throw new IllegalArgumentException("invalid transfer limits");
    }
}
