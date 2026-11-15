package io.vyrnforge.filestorage.spi;

import io.vyrnforge.filestorage.domain.StorageModel.*;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

/** Internal provider port. No vendor types or externally visible locations.
 * Each call has a timeout; adapter enforces bounded I/O (including returned stream reads)
 * and observes interruption. Timeout/transient errors can leave uncertain external effects.
 * Callers reconcile before retrying writes; no provider I/O inside metadata transactions.
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
        public Metadata {
            if (size < 0 || mimeType == null || mimeType.isBlank()
                    || (sha256 != null && !Pattern.matches("[0-9a-f]{64}", sha256))) {
                throw new IllegalArgumentException("invalid object metadata");
            }
        }
    }
    record Page(List<Locator> objects, String nextCursor) {
        public Page { objects=List.copyOf(objects); }
    }
    Set<Capability> capabilities();
    /**
     * Create-only write to a new staged key; concurrent writers cannot replace it.
     * Exact expected size (including zero); reject short/long input without committing
     * a successful object. Caller owns input. Never emulate create-only with HEAD+PUT.
     * A provider version, if supplied, must not be silently ignored.
     */
    Metadata put(Locator object, InputStream bytes, long expectedSize, Call call);
    /** Caller owns returned stream; exact version if specified, no fallback to latest. */
    InputStream get(Locator object,Call call);
    /** SHA-256 is lowercase hex of actual bytes or null; ETag is not SHA-256. */
    Metadata head(Locator object,Call call);
    /** Idempotent deletion of exact target: absent is success, not authorization failure. */
    void delete(Locator object,Call call);
    /** Bounded location/prefix page; opaque continuation bound to that listing; null ends listing. */
    Page list(LocationId location,String prefix,String cursor,int limit,Call call);
    /** Optional capabilities are negotiated; no capability is implemented by this default. */
    default Metadata copy(Locator source,Locator destination,Call call){throw new StorageFailure(ErrorCode.UNSUPPORTED);}
    /** Internal transport instructions only; never serialize as a consumer FileReference or log. */
    record SignedRequest(URI uri, String method, Map<String,String> headers, Map<String,String> formFields, Instant expiresAt) {
        public SignedRequest { Objects.requireNonNull(uri); Objects.requireNonNull(method); headers=Map.copyOf(headers); formFields=Map.copyOf(formFields); Objects.requireNonNull(expiresAt); }
        @Override public String toString(){return "SignedRequest[redacted]";}
    }
    record Multipart(Locator object, String handle) {
        public Multipart { Objects.requireNonNull(object); if(handle==null||handle.isBlank())throw new IllegalArgumentException("handle"); }
        @Override public String toString(){return "Multipart[redacted]";}
    }
    record Part(int number, String receipt) {
        public Part { if(number<1||receipt==null||receipt.isBlank())throw new IllegalArgumentException("part"); }
        @Override public String toString(){return "Part[redacted]";}
    }
    default SignedRequest signUpload(Locator object,long expectedSize,Instant expiry,Call call){throw new StorageFailure(ErrorCode.UNSUPPORTED);}
    default SignedRequest signDownload(Locator object,Instant expiry,Call call){throw new StorageFailure(ErrorCode.UNSUPPORTED);}
    default Multipart beginMultipart(Locator object,Call call){throw new StorageFailure(ErrorCode.UNSUPPORTED);}
    /** Caller closes part stream; adapter validates byte count and bounded I/O. */
    default Part putPart(Multipart upload,int number,InputStream bytes,long expectedSize,Call call){throw new StorageFailure(ErrorCode.UNSUPPORTED);}
    default Metadata completeMultipart(Multipart upload,List<Part> parts,Call call){throw new StorageFailure(ErrorCode.UNSUPPORTED);}
    default void abortMultipart(Multipart upload,Call call){throw new StorageFailure(ErrorCode.UNSUPPORTED);}
}
