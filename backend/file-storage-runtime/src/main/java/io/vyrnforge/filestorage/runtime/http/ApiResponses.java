package io.vyrnforge.filestorage.runtime.http;

import io.vyrnforge.platform.kernel.context.CorrelationContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.Collections;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Common response envelope for MVC and admission-filter rejections. */
final class ApiResponses {
    private ApiResponses() {}

    static <T> ResponseEntity<T> success(CorrelationContext context, T body) {
        return headers(ResponseEntity.ok(), context).body(body);
    }

    static ResponseEntity<FileDtos.ErrorResponse> error(HttpServletRequest request, int status, String code) {
        var context = errorContext(request);
        return headers(ResponseEntity.status(status), context).contentType(MediaType.APPLICATION_JSON).body(errorBody(context, code));
    }

    static void reject(HttpServletRequest request, HttpServletResponse response, int status, String code)
            throws IOException {
        var context = errorContext(request);
        var body = errorBody(context, code);
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("X-Correlation-Id", body.correlationId().toString());
        response.setHeader("X-Request-Id", body.requestId().toString());
        response.setHeader("Cache-Control", "no-store");
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        // Only fixed internal codes and UUID values are serialized; never request text.
        response.getWriter().write("{\"code\":\"" + body.code() + "\",\"correlationId\":\""
                + body.correlationId() + "\",\"requestId\":\"" + body.requestId() + "\"}");
    }

    private static CorrelationContext errorContext(HttpServletRequest request) {
        UUID supplied;
        try { supplied = Collections.list(request.getHeaders("X-Correlation-Id")).size() == 1
                ? ApiRequest.correlation(request) : null; }
        catch (RuntimeException ignored) { supplied = null; }
        return supplied == null ? CorrelationContext.create() : CorrelationContext.create(supplied);
    }

    private static FileDtos.ErrorResponse errorBody(CorrelationContext context, String code) {
        return new FileDtos.ErrorResponse(code, context.correlationId(), context.requestId());
    }

    private static ResponseEntity.BodyBuilder headers(ResponseEntity.BodyBuilder builder, CorrelationContext context) {
        return builder.header("X-Correlation-Id", context.correlationId().toString())
                .header("X-Request-Id", context.requestId().toString()).header("Cache-Control", "no-store");
    }
}
