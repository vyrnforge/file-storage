package io.vyrnforge.filestorage.runtime.http;

import io.vyrnforge.filestorage.domain.StorageModel.FileId;
import io.vyrnforge.filestorage.domain.StorageModel.SessionId;
import io.vyrnforge.filestorage.security.SecuredFiles;
import io.vyrnforge.filestorage.application.FileApplication.*;
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

    @GetMapping(value = "/files", produces = "application/json")
    public ResponseEntity<FileDtos.CatalogResponse> catalog(HttpServletRequest request) {
        var context=ApiRequest.from(request);
        if(!java.util.Set.of("afterFileId","limit").containsAll(request.getParameterMap().keySet()))
            throw new io.vyrnforge.filestorage.application.FileApplication.Failure(
                io.vyrnforge.filestorage.application.FileApplication.Code.INVALID);
        String cursor=ApiRequest.query(request,"afterFileId",null);
        int limit=(int)ApiRequest.number(ApiRequest.query(request,"limit","50"),1,100);
        var result=secured.catalog(context.bearer(),context.tenant(),context.correlationId(),
            cursor==null?null:new FileId(ApiRequest.fileId(cursor)),limit);
        return ApiResponses.success(result.correlation(),FileDtos.catalog(result.value()));
    }

    @GetMapping(value = "/deleted-files", produces = "application/json")
    public ResponseEntity<FileDtos.DeletedResponse> deleted(HttpServletRequest request) {
        var context=ApiRequest.from(request);
        if(!java.util.Set.of("afterFileId","limit").containsAll(request.getParameterMap().keySet()))
            throw new Failure(Code.INVALID);
        String cursor=ApiRequest.query(request,"afterFileId",null);
        int limit=(int)ApiRequest.number(ApiRequest.query(request,"limit","50"),1,100);
        var result=secured.deleted(context.bearer(),context.tenant(),context.correlationId(),
            cursor==null?null:new FileId(ApiRequest.fileId(cursor)),limit);
        return ApiResponses.success(result.correlation(),FileDtos.deleted(result.value()));
    }

    @GetMapping(value = "/usage", produces = "application/json")
    public ResponseEntity<io.vyrnforge.filestorage.application.FileApplication.CatalogUsage> usage(HttpServletRequest request) {
        var context=ApiRequest.from(request);
        if(!request.getParameterMap().isEmpty())
            throw new io.vyrnforge.filestorage.application.FileApplication.Failure(
                io.vyrnforge.filestorage.application.FileApplication.Code.INVALID);
        var result=secured.usage(context.bearer(),context.tenant(),context.correlationId());
        return ApiResponses.success(result.correlation(),result.value());
    }

    @PostMapping(value = "/files/{fileId}/delete", produces = "application/json")
    public ResponseEntity<LifecycleView> delete(HttpServletRequest request,@PathVariable("fileId") String fileId) {
        return lifecycle(request,fileId,Action.DELETE_FILE);
    }

    @PostMapping(value = "/files/{fileId}/restore", produces = "application/json")
    public ResponseEntity<LifecycleView> restore(HttpServletRequest request,@PathVariable("fileId") String fileId) {
        return lifecycle(request,fileId,Action.RESTORE_FILE);
    }

    private ResponseEntity<LifecycleView> lifecycle(HttpServletRequest request,String fileId,Action action) {
        var context=ApiRequest.from(request);
        if(!request.getParameterMap().keySet().equals(java.util.Set.of("expectedRevision")))throw new Failure(Code.INVALID);
        long expected=ApiRequest.number(ApiRequest.query(request,"expectedRevision",null),0,Long.MAX_VALUE-1);
        var result=secured.lifecycle(context.bearer(),context.tenant(),context.correlationId(),new FileId(ApiRequest.fileId(fileId)),action,expected);
        return ApiResponses.success(result.correlation(),result.value());
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
