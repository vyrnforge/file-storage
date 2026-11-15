package io.vyrnforge.filestorage.runtime.http;

import io.vyrnforge.filestorage.domain.StorageModel.VersionId;
import io.vyrnforge.filestorage.runtime.transfer.DurableDownloadAuthority;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Logical version-specific download handle. No provider URL or physical placement. */
@RestController
public final class DownloadCommandRoutes {
    private final DurableDownloadAuthority authority;
    public DownloadCommandRoutes(DurableDownloadAuthority authority){this.authority=Objects.requireNonNull(authority);}

    @PostMapping(value="/v1/files/{fileId}/versions/{versionId}/download-handle",
            consumes="application/json",produces="application/json")
    public ResponseEntity<UploadCommandRoutes.HandleResponse> issue(HttpServletRequest request,
            @PathVariable("fileId") String fileId,@PathVariable("versionId") String versionId,
            @RequestBody UploadCommandRoutes.EmptyRequest body){
        var context=ApiRequest.from(request);
        var result=authority.issue(context.bearer(),context.tenant(),ApiRequest.fileId(fileId),
                new VersionId(ApiRequest.uuid(versionId)),context.correlationId());
        return ApiResponses.success(result.correlation(),new UploadCommandRoutes.HandleResponse(
            result.value().handle(),result.value().expiresAt()));
    }
}
