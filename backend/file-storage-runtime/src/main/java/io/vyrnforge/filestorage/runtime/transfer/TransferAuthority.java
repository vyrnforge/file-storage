package io.vyrnforge.filestorage.runtime.transfer;

/** Trusted control-plane boundary. Authenticate proof, resolve current actor/tenant/app,
 * recheck action, resource, state and exact placement, and atomically consume the
 * opaque single-use handle before returning an immutable instruction.
 * No transaction may remain open after claim. Deny expired, revoked, replayed,
 * mismatched or unknown handles, and authority outage. No caller claims trusted.
 * An attempted transfer consumes its handle even if subsequent I/O fails.
 */
@FunctionalInterface
public interface TransferAuthority {
    TransferInstruction claim(String bearer, String handle, TransferInstruction.Action action);

    /** Only called after successful provider stream and lease close. Legacy fixture authorities have no lease. */
    default void acknowledge(TransferInstruction instruction) {
        if(instruction.readerToken()!=null)throw new TransferFailure(TransferFailure.Code.UNAVAILABLE);
    }
}
