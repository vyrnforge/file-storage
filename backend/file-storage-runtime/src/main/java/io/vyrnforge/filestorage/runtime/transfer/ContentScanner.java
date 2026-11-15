package io.vyrnforge.filestorage.runtime.transfer;

import io.vyrnforge.filestorage.domain.StorageModel.Scan;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;

/** Trusted deployment scanner port, never exposed to consumers.
 * CLEAN/MALICIOUS require reading the full bounded stream; no async retention.
 * Scanner owns content detection and archive/resource safety. No default clean scanner.
 */
@FunctionalInterface
public interface ContentScanner {
    Verdict scan(InputStream content, long exactSize, Duration remaining);

    record Verdict(Scan result,String detectedMime) {
        public Verdict { Objects.requireNonNull(result); }
    }
}
