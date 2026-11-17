package io.vyrnforge.filestorage.security;

import io.vyrnforge.filestorage.security.SecuredFiles.*;
import java.time.Clock;
import java.util.Objects;

/** Shared credential-profile check; authority still belongs to the calling boundary. */
final class AccessAuthentication {
    private AccessAuthentication() {}

    static VerifiedAccess verify(Trust trust, Verifier verifier, Clock clock, String bearer,
                                 SecurityObservation observations, SecurityObservation.Boundary boundary) {
        try {
            if (bearer == null || bearer.isBlank()) throw new SecurityFailure(SecurityCode.AUTHENTICATION_FAILED);
            var verified = Objects.requireNonNull(verifier.verify(bearer));
            var now = clock.instant();
            if (!trust.issuer().equals(verified.issuer()) || !verified.audiences().contains(trust.audience())
                    || verified.kind() != TokenKind.ACCESS || !now.isBefore(verified.expiresAt())
                    || (verified.notBefore() != null && now.isBefore(verified.notBefore()))) {
                throw new SecurityFailure(SecurityCode.AUTHENTICATION_FAILED);
            }
            return verified;
        } catch (RuntimeException ex) {
            var reason = ex instanceof SecurityFailure failure ? failure.reason() : SecurityObservation.Reason.AUTHENTICATION_FAILED;
            // Only the concrete verifier can distinguish a rejected key snapshot from a bad token.
            SecurityObservation.emit(observations, boundary, reason == SecurityObservation.Reason.KEY_SOURCE_UNAVAILABLE
                    ? reason : SecurityObservation.Reason.AUTHENTICATION_FAILED);
            throw new SecurityFailure(SecurityCode.AUTHENTICATION_FAILED);
        }
    }
}
