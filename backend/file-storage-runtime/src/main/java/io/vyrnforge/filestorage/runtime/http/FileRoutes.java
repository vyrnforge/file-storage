package io.vyrnforge.filestorage.runtime.http;

import io.vyrnforge.filestorage.domain.StorageModel.FileId;
import io.vyrnforge.filestorage.domain.StorageModel.SessionId;
import io.vyrnforge.filestorage.security.SecuredFiles;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Thin v1 controller: no storage logic or independent authorization decisions. */
@RestController
@RequestMapping("/v1")
public final class FileRoutes {
    private final SecuredFiles secured;

    public FileRoutes(SecuredFiles secured) {
        this.secured = Objects.requireNonNull(secured);
    }

    @PostMapping(value = "/uploads", consumes = "application/json", produces = "application/json")
    public ResponseEntity<FileDtos.SessionResponse> initiate(HttpServletRequest request,
            @RequestBody FileDtos.Initiation body) {
        var context = ApiRequest.from(request);
        var result = secured.initiate(context.bearer(), context.tenant(), context.correlationId(),
                ApiRequest.initiation(request, body));
        return ApiResponses.success(result.correlation(), FileDtos.session(result.value()));
    }

    @GetMapping(value = "/files/{fileId}", produces = "application/json")
    public ResponseEntity<FileDtos.MetadataResponse> metadata(HttpServletRequest request,
            @PathVariable("fileId") String fileId) {
        var context = ApiRequest.from(request);
        var result = secured.metadata(context.bearer(), context.tenant(), context.correlationId(),
                new FileId(ApiRequest.fileId(fileId)));
        return ApiResponses.success(result.correlation(), FileDtos.metadata(result.value()));
    }

    @GetMapping(value = "/uploads/{sessionId}", produces = "application/json")
    public ResponseEntity<FileDtos.SessionResponse> session(HttpServletRequest request,
            @PathVariable("sessionId") String sessionId) {
        var context = ApiRequest.from(request);
        var result = secured.session(context.bearer(), context.tenant(), context.correlationId(),
                new SessionId(ApiRequest.uuid(sessionId)));
        return ApiResponses.success(result.correlation(), FileDtos.session(result.value()));
    }
}
