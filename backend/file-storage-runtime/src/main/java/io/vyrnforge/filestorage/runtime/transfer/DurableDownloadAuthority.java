package io.vyrnforge.filestorage.runtime.transfer;

import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.filestorage.persistence.JdbcDownloadTransfers;
import io.vyrnforge.filestorage.security.SecuredFiles;
import java.util.Objects;
import java.util.UUID;

/** Fresh authorization at issuance and use, independent from the upload grant. */
public final class DurableDownloadAuthority implements TransferAuthority {
    private final SecuredFiles secured;
    private final JdbcDownloadTransfers handles;

    public DurableDownloadAuthority(SecuredFiles secured,JdbcDownloadTransfers handles){
        this.secured=Objects.requireNonNull(secured);this.handles=Objects.requireNonNull(handles);
    }
    public SecuredFiles.Result<JdbcDownloadTransfers.Issued> issue(String bearer,TenantId tenant,FileId file,
                                                                  VersionId version,UUID correlation){
        var auth=secured.authorizeDownload(bearer,tenant,correlation,file,version);
        return new SecuredFiles.Result<>(handles.issue(auth.value(),file,version,auth.correlation()),auth.correlation());
    }
    @Override public TransferInstruction claim(String bearer,String handle,TransferInstruction.Action action){
        if(action!=TransferInstruction.Action.DOWNLOAD)throw new TransferFailure(TransferFailure.Code.DENIED);
        try{
            var lookup=handles.lookup(handle);
            var auth=secured.authorizeDownload(bearer,lookup.tenant(),null,lookup.file(),lookup.version());
            var pin=handles.claim(handle,auth.value(),lookup.file(),lookup.version(),auth.correlation());
            return new TransferInstruction(pin.caller().tenant(),pin.caller().actor(),pin.caller().consumer(),
                action,pin.file(),pin.version(),null,pin.backend(),pin.revision(),pin.locator(),pin.size(),pin.expiresAt(),pin.digest(),pin.readerToken());
        }catch(RuntimeException ex){throw new TransferFailure(TransferFailure.Code.DENIED);}
    }

    @Override public void acknowledge(TransferInstruction instruction){
        if(instruction.action()!=TransferInstruction.Action.DOWNLOAD||instruction.readerToken()==null
                ||!handles.acknowledge(instruction.tenant(),instruction.file(),instruction.version(),instruction.readerToken()))
            throw new TransferFailure(TransferFailure.Code.UNAVAILABLE);
    }

    /** Select exactly one reviewed authority by requested action; never try another on denial. */
    public static TransferAuthority combine(TransferAuthority uploads,DurableDownloadAuthority downloads){
        Objects.requireNonNull(uploads);Objects.requireNonNull(downloads);
        return new TransferAuthority(){
            @Override public TransferInstruction claim(String bearer,String handle,TransferInstruction.Action action){
                return switch(action){
                    case UPLOAD->uploads.claim(bearer,handle,action);
                    case DOWNLOAD->downloads.claim(bearer,handle,action);
                };
            }
            @Override public void acknowledge(TransferInstruction instruction){
                if(instruction.action()==TransferInstruction.Action.DOWNLOAD)downloads.acknowledge(instruction);
            }
        };
    }
}
