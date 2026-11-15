package io.vyrnforge.filestorage.runtime.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounds metadata before deserialization, even for a chunked request. Not a file-byte endpoint. */
final class ApiAdmissionFilter extends OncePerRequestFilter {
    static final int MAX_METADATA_BYTES = 16 * 1024;
    private static final List<String> SINGLE_HEADERS = List.of("Authorization", "X-Tenant-Id",
            "X-Correlation-Id", "Idempotency-Key", "Content-Type", "Content-Encoding");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        for (String header : SINGLE_HEADERS) {
            var values = Collections.list(request.getHeaders(header));
            if (values.size() > 1 || (!values.isEmpty() && values.getFirst().contains(","))) {
                boolean auth = header.equals("Authorization");
                ApiResponses.reject(request, response, auth ? 401 : 400, auth ? "AUTHENTICATION_FAILED" : "INVALID");
                return;
            }
        }
        if (!"POST".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        // All v1 POSTs are metadata-only in this increment.
        String encoding = request.getHeader("Content-Encoding");
        if (encoding != null && !"identity".equalsIgnoreCase(encoding)) {
            ApiResponses.reject(request, response, 415, "UNSUPPORTED_MEDIA");
            return;
        }
        if (request.getContentLengthLong() > MAX_METADATA_BYTES) {
            ApiResponses.reject(request, response, 413, "PAYLOAD_TOO_LARGE");
            return;
        }
        byte[] bytes = request.getInputStream().readNBytes(MAX_METADATA_BYTES + 1);
        if (bytes.length > MAX_METADATA_BYTES) {
            ApiResponses.reject(request, response, 413, "PAYLOAD_TOO_LARGE");
            return;
        }
        chain.doFilter(new BufferedMetadata(request, bytes), response);
    }

    /** Bounded, synchronous replay of the metadata body for MVC JSON decoding. */
    private static final class BufferedMetadata extends HttpServletRequestWrapper {
        private final ServletInputStream input;

        BufferedMetadata(HttpServletRequest request, byte[] bytes) {
            super(request);
            var buffer = new ByteArrayInputStream(bytes);
            input = new ServletInputStream() {
                @Override public int read() { return buffer.read(); }
                @Override public int read(byte[] b, int off, int len) { return buffer.read(b, off, len); }
                @Override public boolean isFinished() { return buffer.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    throw new IllegalStateException("asynchronous metadata reads unsupported");
                }
            };
        }

        @Override public ServletInputStream getInputStream() { return input; }
    }
}
