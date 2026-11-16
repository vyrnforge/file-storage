package io.vyrnforge.filestorage.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.KeyOperation;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.time.*;
import java.util.*;

/** Wyrmgate access-token profile. No token-directed key discovery or unverified identity mapping. */
public final class WyrmgateJwtVerifier implements SecuredFiles.Verifier {
    private static final Set<JWSAlgorithm> RSA = Set.of(JWSAlgorithm.RS256,JWSAlgorithm.RS384,JWSAlgorithm.RS512);
    private static final int MAX_TOKEN=16384, MAX_JWKS=65536, MAX_KEYS=32;

    /** Acquired by a trusted, server-configured source, never from token headers. */
    public record KeySnapshot(String issuer,String jwks,Instant acquiredAt) {
        public KeySnapshot {
            Objects.requireNonNull(issuer);Objects.requireNonNull(jwks);Objects.requireNonNull(acquiredAt);
        }
        @Override public String toString(){return "KeySnapshot[redacted]";}
    }
    @FunctionalInterface public interface KeySource { KeySnapshot current(); }

    private final SecuredFiles.Trust trust;
    private final Set<JWSAlgorithm> algorithms;
    private final KeySource keys;
    private final Clock clock;
    private final Duration maxTokenLifetime,maxSnapshotAge;

    public WyrmgateJwtVerifier(SecuredFiles.Trust trust,Set<JWSAlgorithm> algorithms,KeySource keys,
                              Clock clock,Duration maxTokenLifetime,Duration maxSnapshotAge){
        this.trust=Objects.requireNonNull(trust);
        URI issuer=URI.create(trust.issuer());
        if(!"https".equalsIgnoreCase(issuer.getScheme())||issuer.getHost()==null||issuer.getUserInfo()!=null
                ||issuer.getQuery()!=null||issuer.getFragment()!=null)throw new IllegalArgumentException("invalid trusted issuer");
        this.algorithms=Set.copyOf(algorithms);
        if(this.algorithms.isEmpty()||!RSA.containsAll(this.algorithms))throw new IllegalArgumentException("unsupported algorithm");
        this.keys=Objects.requireNonNull(keys);this.clock=Objects.requireNonNull(clock);
        this.maxTokenLifetime=positive(maxTokenLifetime);this.maxSnapshotAge=positive(maxSnapshotAge);
    }
    /** Reviewed staging profile; callers cannot select algorithms from token or configuration. */
    public static WyrmgateJwtVerifier rs256(SecuredFiles.Trust trust,KeySource source,Clock clock,
            Duration maxTokenLifetime,Duration maxSnapshotAge){
        return new WyrmgateJwtVerifier(trust,Set.of(JWSAlgorithm.RS256),source,clock,maxTokenLifetime,maxSnapshotAge);
    }
    /** Readiness validates the full trusted public-key snapshot, not merely file existence. */
    public boolean ready(){
        try{
            var snapshot=Objects.requireNonNull(keys.current());
            var now=clock.instant();
            if(!trust.issuer().equals(snapshot.issuer())||snapshot.jwks().length()>MAX_JWKS||snapshot.acquiredAt().isAfter(now)
                ||Duration.between(snapshot.acquiredAt(),now).compareTo(maxSnapshotAge)>=0)return false;
            var list=JWKSet.parse(snapshot.jwks()).getKeys();
            var seen=new HashSet<String>();
            if(list.isEmpty()||list.size()>MAX_KEYS)return false;
            boolean usable=false;
            for(var key:list){
                if(!(key instanceof RSAKey rsa)||rsa.isPrivate()||rsa.size()<2048||rsa.getKeyID()==null||rsa.getKeyID().isBlank()
                    ||rsa.getKeyID().length()>256||!seen.add(rsa.getKeyID())||!KeyUse.SIGNATURE.equals(rsa.getKeyUse())
                    ||!RSA.contains(rsa.getAlgorithm())||(rsa.getKeyOperations()!=null&&!rsa.getKeyOperations().equals(Set.of(KeyOperation.VERIFY))))return false;
                usable|=algorithms.contains(rsa.getAlgorithm());
            }
            return usable;
        }catch(Exception ex){return false;}
    }
    private static Duration positive(Duration d){
        Objects.requireNonNull(d);if(d.isZero()||d.isNegative())throw new IllegalArgumentException("invalid lifetime");return d;
    }
    private static SecuredFiles.SecurityFailure denied(){return new SecuredFiles.SecurityFailure(SecuredFiles.SecurityCode.AUTHENTICATION_FAILED);}
    @Override public SecuredFiles.VerifiedAccess verify(String bearer){
        try{
            if(bearer==null||bearer.length()>MAX_TOKEN||bearer.isBlank())throw denied();
            SignedJWT jwt=SignedJWT.parse(bearer);
            JWSHeader h=jwt.getHeader();
            if(!algorithms.contains(h.getAlgorithm())||!new JOSEObjectType("at+jwt").equals(h.getType())
                    ||h.getKeyID()==null||h.getKeyID().isBlank()||h.getKeyID().length()>256
                    ||(h.getCriticalParams()!=null&&!h.getCriticalParams().isEmpty())
                    ||h.getJWKURL()!=null||h.getJWK()!=null||h.getX509CertURL()!=null||h.getX509CertChain()!=null
                    ||h.getX509CertThumbprint()!=null||h.getX509CertSHA256Thumbprint()!=null
                    ||!h.isBase64URLEncodePayload())throw denied();
            Instant now=clock.instant();
            RSAKey key=selectKey(h,now);
            if(!jwt.verify(new RSASSAVerifier(key.toRSAPublicKey())))throw denied();
            var c=jwt.getJWTClaimsSet();
            var iat=c.getIssueTime();var exp=c.getExpirationTime();var nbf=c.getNotBeforeTime();
            if(!trust.issuer().equals(c.getIssuer())||c.getSubject()==null||c.getSubject().isBlank()
                    ||!List.of(trust.audience()).equals(c.getAudience())
                    ||!trust.audience().equals(c.getStringClaim("client_id"))
                    ||iat==null||exp==null||iat.toInstant().isAfter(now)||!now.isBefore(exp.toInstant())
                    ||!exp.toInstant().isAfter(iat.toInstant())
                    ||Duration.between(iat.toInstant(),exp.toInstant()).compareTo(maxTokenLifetime)>0
                    ||(nbf!=null&&(now.isBefore(nbf.toInstant())||!nbf.toInstant().isBefore(exp.toInstant())))
                    ||c.getClaim("act")!=null||c.getClaim("cnf")!=null)throw denied();
            return new SecuredFiles.VerifiedAccess(c.getIssuer(),c.getSubject(),Set.of(trust.audience()),
                    SecuredFiles.TokenKind.ACCESS,nbf==null?null:nbf.toInstant(),exp.toInstant(),null);
        }catch(SecuredFiles.SecurityFailure ex){throw ex;}
        catch(Exception ex){throw denied();}
    }
    private RSAKey selectKey(JWSHeader h,Instant now) {
        RSAKey selected=null;
        try {
            KeySnapshot snapshot=Objects.requireNonNull(keys.current());
            if(!trust.issuer().equals(snapshot.issuer())||snapshot.jwks().length()>MAX_JWKS
                    ||snapshot.acquiredAt().isAfter(now)
                    ||Duration.between(snapshot.acquiredAt(),now).compareTo(maxSnapshotAge)>=0)throw denied();
            List<JWK> set=JWKSet.parse(snapshot.jwks()).getKeys();
            if(set.isEmpty()||set.size()>MAX_KEYS)throw denied();
            Set<String> seen=new HashSet<>();
            boolean usable=false;
            for(JWK jwk:set){
                if(!(jwk instanceof RSAKey rsa)||rsa.isPrivate()||rsa.size()<2048
                        ||rsa.getKeyID()==null||rsa.getKeyID().isBlank()||rsa.getKeyID().length()>256
                        ||!seen.add(rsa.getKeyID())||!KeyUse.SIGNATURE.equals(rsa.getKeyUse())
                        ||!RSA.contains(rsa.getAlgorithm())
                        ||(rsa.getKeyOperations()!=null&&!rsa.getKeyOperations().equals(Set.of(KeyOperation.VERIFY))))
                    throw denied();
                usable|=algorithms.contains(rsa.getAlgorithm());
                if(h.getKeyID().equals(rsa.getKeyID()))selected=rsa;
            }
            if(!usable)throw denied();
        }catch(java.text.ParseException|RuntimeException ex){throw new SecuredFiles.SecurityFailure(SecuredFiles.SecurityCode.AUTHENTICATION_FAILED,
                SecurityObservation.Reason.KEY_SOURCE_UNAVAILABLE);}
        if(selected==null||!h.getAlgorithm().equals(selected.getAlgorithm()))throw denied();
        return selected;
    }
}
