package io.vyrnforge.filestorage.runtime.transfer;

import io.vyrnforge.filestorage.runtime.transfer.TransferFailure.Code;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import org.springframework.web.bind.annotation.*;

/** Private gateway transport. Handle is a sensitive header, not a URL or provider locator. */
@RestController
@RequestMapping("/transfer/v1")
public final class TransferRoutes {
    private final PrivateTransferGateway gateway;
    TransferRoutes(PrivateTransferGateway gateway) { this.gateway = gateway; }

    @PutMapping("/upload")
    public void upload(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        try {
            var proof = proof(request);
            if (!"application/octet-stream".equalsIgnoreCase(single(request, "Content-Type")))
                throw new TransferFailure(Code.INVALID);
            gateway.upload(proof.bearer(), proof.handle(), request.getContentLengthLong(), request.getInputStream());
            response.setStatus(204);
        } catch (TransferFailure ex) { reject(response, ex); }
    }

    @GetMapping("/download")
    public void download(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        try {
            if (!"GET".equals(request.getMethod())) throw new TransferFailure(Code.INVALID);
            var proof = proof(request);
            response.setContentType("application/octet-stream");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Content-Disposition", "attachment");
            gateway.download(proof.bearer(), proof.handle(), response::setContentLengthLong, response.getOutputStream());
        } catch (TransferFailure ex) { reject(response, ex); }
    }

    private record Proof(String bearer, String handle) {
        @Override public String toString() { return "Proof[redacted]"; }
    }

    private static Proof proof(HttpServletRequest request) {
        String auth = single(request, "Authorization");
        String handle = single(request, "X-Transfer-Handle");
        if (auth == null || !auth.startsWith("Bearer ") || handle == null) throw new TransferFailure(Code.DENIED);
        if (request.getHeader("Range") != null || request.getHeader("Content-Range") != null
                || request.getHeader("X-Tenant-Id") != null) throw new TransferFailure(Code.INVALID);
        String encoding = single(request, "Content-Encoding");
        if (encoding != null && !"identity".equalsIgnoreCase(encoding)) throw new TransferFailure(Code.INVALID);
        return new Proof(auth.substring(7), handle);
    }

    private static String single(HttpServletRequest request, String name) {
        var values = Collections.list(request.getHeaders(name));
        if (values.size() > 1 || (!values.isEmpty() && values.getFirst().contains(",")))
            throw new TransferFailure(Code.INVALID);
        return values.isEmpty() ? null : values.getFirst();
    }

    private static void reject(HttpServletResponse response, TransferFailure failure) throws IOException {
        // Once bytes are committed, never append JSON or claim a successful retry.
        if (response.isCommitted()) throw new IOException("transfer interrupted");
        response.reset();
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json");
        response.setStatus(switch (failure.code()) {
            case INVALID -> 400;
            case DENIED -> 403;
            case BUSY -> 429;
            case TOO_LARGE -> 413;
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
            case TIMEOUT -> 504;
            case UNAVAILABLE -> 503;
        });
        response.getOutputStream().write(("{\"code\":\""+failure.code().name()+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
