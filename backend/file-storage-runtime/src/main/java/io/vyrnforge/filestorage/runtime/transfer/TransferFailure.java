package io.vyrnforge.filestorage.runtime.transfer;

/** Public-safe failure; no original message, cause, handle or placement. */
public final class TransferFailure extends RuntimeException {
    public enum Code { INVALID, DENIED, BUSY, TOO_LARGE, NOT_FOUND, CONFLICT, TIMEOUT, UNAVAILABLE }
    private final Code code;
    public TransferFailure(Code code) { super(code.name()); this.code = code; }
    public Code code() { return code; }
}
