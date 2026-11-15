package io.vyrnforge.filestorage.runtime.http;

import io.vyrnforge.filestorage.application.FileApplication.Code;
import io.vyrnforge.filestorage.application.FileApplication.Failure;
import io.vyrnforge.filestorage.application.FileApplication.Initiate;
import io.vyrnforge.filestorage.domain.StorageModel.TenantId;
import io.vyrnforge.filestorage.security.SecuredFiles.SecurityCode;
import io.vyrnforge.filestorage.security.SecuredFiles.SecurityFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

/** Request-derived values are untrusted until SecuredFiles verifies the bearer and scope. */
record ApiRequest(String bearer, TenantId tenant, UUID correlationId) {
    static ApiRequest from(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ") || header.length() > 16384
                || header.substring(7).isBlank() || header.substring(7).chars().anyMatch(Character::isWhitespace)) {
            throw new SecurityFailure(SecurityCode.AUTHENTICATION_FAILED);
        }
        TenantId tenant;
        try {
            tenant = new TenantId(uuid(request.getHeader("X-Tenant-Id")));
        } catch (IllegalArgumentException ex) {
            throw new Failure(Code.INVALID);
        }
        return new ApiRequest(header.substring(7), tenant, correlation(request));
    }

    static Initiate initiation(HttpServletRequest request, FileDtos.Initiation body) {
        if (body == null || body.expectedSize() == null) throw new Failure(Code.INVALID);
        return new Initiate(bounded(body.purpose(), 128), bounded(body.name(), 512),
                body.expectedSize(), bounded(body.mimeType(), 255),
                bounded(request.getHeader("Idempotency-Key"), 128));
    }

    static UUID correlation(HttpServletRequest request) {
        String value = request.getHeader("X-Correlation-Id");
        return value == null ? null : uuid(value);
    }

    static UUID uuid(String value) {
        if (value == null || value.length() != 36) throw new Failure(Code.INVALID);
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value)) throw new Failure(Code.INVALID);
            return parsed;
        } catch (IllegalArgumentException ex) {
            throw new Failure(Code.INVALID);
        }
    }

    static String bounded(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new Failure(Code.INVALID);
        }
        return value;
    }

    @Override public String toString() { return "ApiRequest[redacted]"; }
}
