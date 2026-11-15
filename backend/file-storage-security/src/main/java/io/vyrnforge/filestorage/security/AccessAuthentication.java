package io.vyrnforge.filestorage.security;

import io.vyrnforge.filestorage.security.SecuredFiles.*;
import java.time.Clock;
import java.util.Objects;

/** Shared credential-profile check; authority still belongs to the calling boundary. */
final class AccessAuthentication {
    private AccessAuthentication() {}

    static VerifiedAccess verify(Trust trust, Verifier verifier, Clock clock, String bearer) {
        if (bearer == null || bearer.isBlank()) throw new SecurityFailure(SecurityCode.AUTHENTICATION_FAILED);
        try {
            var verified = Objects.requireNonNull(verifier.verify(bearer));
            var now = clock.instant();
            if (!trust.issuer().equals(verified.issuer()) || !verified.audiences().contains(trust.audience())
                    || verified.kind() != TokenKind.ACCESS || !now.isBefore(verified.expiresAt())
                    || (verified.notBefore() != null && now.isBefore(verified.notBefore()))) {
                throw new SecurityFailure(SecurityCode.AUTHENTICATION_FAILED);
            }
            return verified;
        } catch (RuntimeException ex) {
            throw new SecurityFailure(SecurityCode.AUTHENTICATION_FAILED);
        }
    }
}
