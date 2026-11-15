package io.vyrnforge.filestorage.runtime.http;

import io.vyrnforge.filestorage.domain.StorageModel.SessionId;
import io.vyrnforge.filestorage.runtime.transfer.CompletionCommands;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Logical upload commands. Only the one-time handle response contains a sensitive value. */
@RestController
@RequestMapping("/v1/uploads/{sessionId}")
public final class UploadCommandRoutes {
    private final CompletionCommands commands;
    public UploadCommandRoutes(CompletionCommands commands){this.commands=Objects.requireNonNull(commands);}
    public record EmptyRequest(){}
    public record CompletionRequest(String sha256){}
    public record HandleResponse(String handle,Instant expiresAt){
        @Override public String toString(){return "HandleResponse[redacted]";}
    }
    public record AcceptedResponse(UUID sessionId,UUID operationId,String status,String uploadState,String versionState){}

    @PostMapping(value="/transfer-handle",consumes="application/json",produces="application/json")
    public ResponseEntity<HandleResponse> issue(HttpServletRequest request,@PathVariable("sessionId") String sessionId,
                                                 @RequestBody EmptyRequest body){
        var context=ApiRequest.from(request);
        var result=commands.issue(context.bearer(),context.tenant(),new SessionId(ApiRequest.uuid(sessionId)),context.correlationId());
        return ApiResponses.success(result.correlation(),new HandleResponse(result.value().handle(),result.value().expiresAt()));
    }

    @PostMapping(value="/complete",consumes="application/json",produces="application/json")
    public ResponseEntity<AcceptedResponse> complete(HttpServletRequest request,@PathVariable("sessionId") String sessionId,
                                                     @RequestBody CompletionRequest body){
        var context=ApiRequest.from(request);
        var result=commands.complete(context.bearer(),context.tenant(),new SessionId(ApiRequest.uuid(sessionId)),
                body.sha256(),context.correlationId());
        var accepted=result.value();
        var response=new AcceptedResponse(accepted.session().value(),accepted.operation(),accepted.status(),
                accepted.uploadState().name(),accepted.versionState().name());
        return switch(accepted.uploadState()){
            case COMPLETED,FAILED,EXPIRED -> ApiResponses.success(result.correlation(),response);
            default -> ApiResponses.accepted(result.correlation(),response);
        };
    }
}
