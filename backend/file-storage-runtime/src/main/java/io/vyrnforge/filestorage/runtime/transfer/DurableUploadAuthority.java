package io.vyrnforge.filestorage.runtime.transfer;

import io.vyrnforge.filestorage.domain.StorageModel.SessionId;
import io.vyrnforge.filestorage.domain.StorageModel.TenantId;
import io.vyrnforge.filestorage.persistence.JdbcUploadTransfers;
import io.vyrnforge.filestorage.security.SecuredFiles;
import java.util.Objects;
import java.util.UUID;

/** Trusted composition; uses the existing secured file facade, not caller-supplied identity. */
public final class DurableUploadAuthority implements TransferAuthority {
    private final SecuredFiles secured;
    private final JdbcUploadTransfers handles;

    public DurableUploadAuthority(SecuredFiles secured, JdbcUploadTransfers handles) {
        this.secured = Objects.requireNonNull(secured);
        this.handles = Objects.requireNonNull(handles);
    }

    /** Internal issuance, not registered as a public API in this increment. */
    public JdbcUploadTransfers.Issued issue(String bearer, TenantId tenant, SessionId session, UUID correlation) {
        var result = secured.authorizeUpload(bearer, tenant, correlation, session);
        return handles.issue(result.value(), session, result.correlation());
    }

    @Override public TransferInstruction claim(String bearer, String handle, TransferInstruction.Action action) {
        if (action != TransferInstruction.Action.UPLOAD) throw new TransferFailure(TransferFailure.Code.DENIED);
        try {
            var lookup = handles.lookup(handle);
            var result = secured.authorizeUpload(bearer, lookup.tenant(), null, lookup.session());
            var pin = handles.claim(handle, result.value(), lookup.session(), result.correlation());
            return new TransferInstruction(pin.caller().tenant(), pin.caller().actor(), pin.caller().consumer(),
                    action, pin.file(), pin.version(), pin.session(), pin.backend(), pin.revision(),
                    pin.locator(), pin.size(), pin.expiresAt());
        } catch (RuntimeException ex) { throw new TransferFailure(TransferFailure.Code.DENIED); }
    }
}
