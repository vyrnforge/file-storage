package io.vyrnforge.filestorage.runtime;

import io.vyrnforge.filestorage.application.FileApplication.*;
import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.filestorage.security.SecuredFiles;
import io.vyrnforge.platform.kernel.context.CorrelationContext;
import io.vyrnforge.platform.kernel.file.FileReference;
import java.time.Instant;
import java.util.UUID;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import jakarta.servlet.http.HttpServletRequest;

/** Proposed v1 adapter. Not imported by the executable; tests explicitly register it. */
@RestController
@RequestMapping("/v1")
public final class FileRoutes {
    private final SecuredFiles secured;
    public FileRoutes(SecuredFiles secured) { this.secured = Objects.requireNonNull(secured); }

    public record Initiation(String purpose, String name, Long expectedSize, String mimeType) {}
    public record SessionResponse(FileReference file, String versionId, String fileState,
            String versionState, String sessionId, String uploadState, Instant expiresAt) {}
    public record MetadataResponse(FileReference file, String versionId, String fileState,
            String versionState, String name, long declaredSize, String declaredMimeType) {}
    public record ErrorResponse(String code, UUID correlationId, UUID requestId) {}

    @PostMapping(value="/uploads", consumes="application/json", produces="application/json")
    public ResponseEntity<SessionResponse> initiate(HttpServletRequest request, @RequestBody Initiation body) {
        if (body == null || body.expectedSize() == null) throw new Failure(Code.INVALID);
        var result = secured.initiate(bearer(request), tenant(request), correlation(request),
                new Initiate(bounded(body.purpose(),128), bounded(body.name(),512),
                    body.expectedSize(), bounded(body.mimeType(),255), bounded(request.getHeader("Idempotency-Key"),128)));
        return response(result.correlation(), sessionResponse(result.value()));
    }

    @GetMapping(value="/files/{fileId}", produces="application/json")
    public ResponseEntity<MetadataResponse> metadata(HttpServletRequest request, @PathVariable("fileId") String fileId) {
        var result = secured.metadata(bearer(request), tenant(request), correlation(request), new FileId(fileId));
        var v = result.value();
        return response(result.correlation(), new MetadataResponse(v.file(), v.version().value().toString(),
                v.fileState().name(), v.versionState().name(), v.name(), v.declaredSize(), v.declaredMimeType()));
    }

    @GetMapping(value="/uploads/{sessionId}", produces="application/json")
    public ResponseEntity<SessionResponse> session(HttpServletRequest request, @PathVariable("sessionId") String sessionId) {
        var result = secured.session(bearer(request), tenant(request), correlation(request), new SessionId(uuid(sessionId)));
        return response(result.correlation(), sessionResponse(result.value()));
    }

    private static SessionResponse sessionResponse(View v) {
        return new SessionResponse(v.file(), v.version().value().toString(), v.fileState().name(),
                v.versionState().name(), v.session().value().toString(), v.uploadState().name(), v.expiresAt());
    }

    private static <T> ResponseEntity<T> response(CorrelationContext c, T value) {
        return ResponseEntity.ok().header("X-Correlation-Id", c.correlationId().toString())
                .header("X-Request-Id", c.requestId().toString()).header("Cache-Control","no-store").body(value);
    }

    private static String bearer(HttpServletRequest r) {
        String h = r.getHeader("Authorization");
        if (h == null || !h.startsWith("Bearer ") || h.length() > 16384 || h.substring(7).isBlank())
            throw new SecuredFiles.SecurityFailure(SecuredFiles.SecurityCode.AUTHENTICATION_FAILED);
        return h.substring(7);
    }
    private static TenantId tenant(HttpServletRequest r) { return new TenantId(uuid(r.getHeader("X-Tenant-Id"))); }
    private static UUID correlation(HttpServletRequest r) {
        String h = r.getHeader("X-Correlation-Id");
        return h == null ? null : uuid(h);
    }
    private static UUID uuid(String s) {
        if (s == null || s.length() != 36) throw new Failure(Code.INVALID);
        return UUID.fromString(s);
    }
    private static String bounded(String s, int limit) {
        if (s == null || s.isBlank() || s.length() > limit || s.codePoints().anyMatch(Character::isISOControl))
            throw new Failure(Code.INVALID);
        return s;
    }

    @ExceptionHandler({RuntimeException.class})
    public ResponseEntity<ErrorResponse> error(RuntimeException ex, HttpServletRequest request) {
        int status = 503;
        String code = "UNAVAILABLE";
        if (ex instanceof SecuredFiles.SecurityFailure failure) {
            status = failure.code() == SecuredFiles.SecurityCode.AUTHENTICATION_FAILED ? 401 : 403;
            code = failure.code().name();
        } else if (ex instanceof Failure failure) {
            code = failure.code().name();
            status = switch (failure.code()) {
                case INVALID -> 400;
                case DENIED -> 403;
                case NOT_FOUND -> 404;
                case CONFLICT, IN_PROGRESS -> 409;
                case NO_PLACEMENT -> 503;
            };
        } else if (ex instanceof IllegalArgumentException || ex instanceof HttpMessageNotReadableException) {
            status = 400;
            code = "INVALID";
        }
        UUID supplied;
        try { supplied = correlation(request); } catch (RuntimeException ignored) { supplied = null; }
        var c = supplied == null ? CorrelationContext.create() : CorrelationContext.create(supplied);
        return ResponseEntity.status(status).header("X-Correlation-Id", c.correlationId().toString())
                .header("X-Request-Id", c.requestId().toString()).header("Cache-Control","no-store")
                .body(new ErrorResponse(code, c.correlationId(), c.requestId()));
    }
}
