package io.vyrnforge.filestorage.runtime.transfer;

import io.vyrnforge.filestorage.domain.StorageModel.SessionId;
import io.vyrnforge.filestorage.domain.StorageModel.TenantId;
import io.vyrnforge.filestorage.persistence.JdbcCompletionWork;
import io.vyrnforge.filestorage.persistence.JdbcUploadTransfers;
import io.vyrnforge.filestorage.security.SecuredFiles;
import java.util.Objects;
import java.util.UUID;

/** Explicit secured control-plane composition, never persists bearer proof. */
public final class CompletionCommands {
    private final SecuredFiles secured;
    private final JdbcUploadTransfers handles;
    private final JdbcCompletionWork work;
    public CompletionCommands(SecuredFiles secured,JdbcUploadTransfers handles,JdbcCompletionWork work){
        this.secured=Objects.requireNonNull(secured);this.handles=Objects.requireNonNull(handles);this.work=Objects.requireNonNull(work);
    }
    public SecuredFiles.Result<JdbcUploadTransfers.Issued> issue(String bearer,TenantId tenant,SessionId session,UUID correlation){
        var auth=secured.authorizeUpload(bearer,tenant,correlation,session);
        return new SecuredFiles.Result<>(handles.issue(auth.value(),session,auth.correlation()),auth.correlation());
    }
    public SecuredFiles.Result<JdbcCompletionWork.Accepted> complete(String bearer,TenantId tenant,SessionId session,String digest,UUID correlation){
        var auth=secured.authorizeCompletion(bearer,tenant,correlation,session);
        return new SecuredFiles.Result<>(work.request(auth.value(),session,digest,auth.correlation()),auth.correlation());
    }
}
