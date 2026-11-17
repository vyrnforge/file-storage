package io.vyrnforge.filestorage.security;

import java.util.function.Supplier;

/** Trusted, bounded failure telemetry. No request data, exception or identity is passed to the sink.
 * Counts are boundary observations, not unique failed requests or durable audit events.
 */
@FunctionalInterface
public interface SecurityObservation {
    enum Boundary { FILES, POLICY_ADMIN, BACKEND_ADMIN }
    enum Reason { AUTHENTICATION_FAILED, AUTHORIZATION_DENIED, KEY_SOURCE_UNAVAILABLE,
        BINDING_UNAVAILABLE, MEMBERSHIP_UNAVAILABLE, ENTITLEMENT_UNAVAILABLE,
        GRANT_SOURCE_UNAVAILABLE, PERMISSION_UNAVAILABLE }

    SecurityObservation NONE = (boundary, reason) -> {};

    void record(Boundary boundary, Reason reason);

    /** A telemetry sink failure must neither grant access nor replace the original decision. */
    static void emit(SecurityObservation sink, Boundary boundary, Reason reason) {
        try { sink.record(boundary, reason); }
        catch (RuntimeException ignored) { /* telemetry is not an authorization dependency */ }
    }

    /** Report trusted authority failure and preserve the existing exception path. */
    static <T> T authority(SecurityObservation sink, Boundary boundary, Reason reason, Supplier<T> lookup) {
        try { return lookup.get(); }
        catch (RuntimeException ex) { emit(sink, boundary, reason); throw ex; }
    }
}
