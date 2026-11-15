package io.vyrnforge.filestorage.runtime.http;

import io.vyrnforge.filestorage.application.FileApplication.Failure;
import io.vyrnforge.filestorage.security.SecuredFiles.SecurityFailure;
import io.vyrnforge.filestorage.security.SecuredFiles.SecurityCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Neutral transport errors. No exception messages or request content in responses. */
@RestControllerAdvice
public final class ApiErrors {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<FileDtos.ErrorResponse> error(Exception ex, HttpServletRequest request, HttpServletResponse response) throws Exception {
        // A data-plane stream may already have emitted bytes in COMBINED mode.
        // Never append a metadata error envelope to a committed response.
        if (response.isCommitted()) throw ex;
        if (ex instanceof SecurityFailure failure) {
            int status = failure.code() == SecurityCode.AUTHENTICATION_FAILED ? 401 : 403;
            var result = ApiResponses.error(request, status, failure.code().name());
            if (status == 401) {
                return ResponseEntity.status(status).headers(result.getHeaders())
                        .header("WWW-Authenticate", "Bearer").body(result.getBody());
            }
            return result;
        }
        if (ex instanceof Failure failure) {
            int status = switch (failure.code()) {
                case INVALID -> 400;
                case DENIED -> 403;
                case NOT_FOUND -> 404;
                case CONFLICT, IN_PROGRESS -> 409;
                case NO_PLACEMENT -> 503;
            };
            return ApiResponses.error(request, status, failure.code().name());
        }
        if (ex instanceof HttpMessageNotReadableException) return ApiResponses.error(request, 400, "INVALID");
        if (ex instanceof HttpMediaTypeNotSupportedException) return ApiResponses.error(request, 415, "UNSUPPORTED_MEDIA");
        if (ex instanceof HttpMediaTypeNotAcceptableException) return ApiResponses.error(request, 406, "NOT_ACCEPTABLE");
        if (ex instanceof HttpRequestMethodNotSupportedException method) {
            var result = ApiResponses.error(request, 405, "METHOD_NOT_ALLOWED");
            var builder = ResponseEntity.status(405).headers(result.getHeaders());
            if (method.getSupportedMethods() != null) builder.header("Allow", String.join(", ", method.getSupportedMethods()));
            return builder.body(result.getBody());
        }
        if (ex instanceof NoResourceFoundException) return ApiResponses.error(request, 404, "NOT_FOUND");
        return ApiResponses.error(request, 503, "UNAVAILABLE");
    }
}
