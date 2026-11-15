package io.vyrnforge.filestorage.runtime;

/** Role names describe runtime responsibility, not operational readiness. */
public enum RuntimeRole {
    API, TRANSFER, WORKER, COMBINED;

    public boolean supportsMetadataApi() {
        return this == API || this == COMBINED;
    }

    public static RuntimeRole parse(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new IllegalStateException("invalid runtime role");
        }
    }
}
