package io.vyrnforge.filestorage.spi;

import io.vyrnforge.filestorage.domain.StorageModel.*;
import java.io.InputStream;
import java.time.Duration;
import java.util.*;

/** Internal provider port. No vendor types or externally visible locations.
 * Each call has a deadline; adapter enforces bounded I/O and observes thread interruption.
 * Caller closes put stream; caller closes returned get stream. Closing get must release resources.
 * Failures use neutral codes; no raw provider response, credential or URL in error text.
 */
public interface StorageProvider {
    enum Capability { COPY, PRESIGN, MULTIPART }
    enum ErrorCode { NOT_FOUND, CONFLICT, FORBIDDEN, TIMEOUT, TRANSIENT, UNSUPPORTED, INVALID }
    final class StorageFailure extends RuntimeException {
        private final ErrorCode code;
        public StorageFailure(ErrorCode code){ super(Objects.requireNonNull(code).name()); this.code=code; }
        public ErrorCode code(){return code;}
    }
    record Call(Duration timeout) {
        public Call { Objects.requireNonNull(timeout); if(timeout.isNegative()||timeout.isZero())throw new IllegalArgumentException("timeout"); }
    }
    record Metadata(long size,String sha256,String mimeType) {
        public Metadata { if(size<0)throw new IllegalArgumentException("size"); Objects.requireNonNull(mimeType); }
    }
    record Page(List<Locator> objects, String nextCursor) {
        public Page { objects=List.copyOf(objects); }
    }
    Set<Capability> capabilities();
    /** Writes a new staged key; must not silently overwrite verified content. */
    Metadata put(Locator object, InputStream bytes, long expectedSize, Call call);
    InputStream get(Locator object,Call call);
    Metadata head(Locator object,Call call);
    /** Idempotent deletion: absent object is success. */
    void delete(Locator object,Call call);
    Page list(LocationId location,String prefix,String cursor,int limit,Call call);
    /** Optional capabilities are negotiated; no capability is implemented by this default. */
    default Metadata copy(Locator source,Locator destination,Call call){throw new StorageFailure(ErrorCode.UNSUPPORTED);}
    // Signing and multipart contracts are deferred until adapter-specific security design in FS-200.
}
