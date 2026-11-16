package io.vyrnforge.filestorage.security;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import io.vyrnforge.filestorage.security.SecuredFiles.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class WyrmgateJwtVerifierTest {
    static final Instant NOW=SecuredFilesTest.NOW;
    static final String ISS=SecuredFilesTest.ISS,AUD=SecuredFilesTest.AUD;
    static RSAKey current,previous,other;
    @BeforeAll static void generate() throws Exception {
        current=key("current");previous=key("previous");other=key("other");
    }
    static RSAKey key(String id) throws Exception {
        return new RSAKeyGenerator(2048).keyID(id).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();
    }
    static String jwks(RSAKey... keys){
        return new JWKSet(Arrays.stream(keys).map(RSAKey::toPublicJWK).map(k->(JWK)k).toList()).toString();
    }
    WyrmgateJwtVerifier.KeySnapshot snapshot;
    boolean unavailable;
    WyrmgateJwtVerifier verifier(){
        return new WyrmgateJwtVerifier(new Trust(ISS,AUD),Set.of(JWSAlgorithm.RS256),()->{
            if(unavailable)throw new IllegalStateException("sensitive internal failure");
            return snapshot==null?new WyrmgateJwtVerifier.KeySnapshot(ISS,jwks(current),NOW):snapshot;
        },Clock.fixed(NOW,ZoneOffset.UTC),Duration.ofMinutes(5),Duration.ofMinutes(10));
    }
    static JWTClaimsSet.Builder claims(){
        return new JWTClaimsSet.Builder().issuer(ISS).subject("alice").audience(AUD).claim("client_id",AUD)
                .issueTime(Date.from(NOW.minusSeconds(30))).expirationTime(Date.from(NOW.plusSeconds(60)));
    }
    static JWSHeader.Builder header(String kid){
        return new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).type(new JOSEObjectType("at+jwt"));
    }
    static String sign(RSAKey key,JWSHeader.Builder h,JWTClaimsSet.Builder c) throws Exception{
        SignedJWT jwt=new SignedJWT(h.build(),c.build());jwt.sign(new RSASSASigner(key));return jwt.serialize();
    }
    static String valid(RSAKey key) throws Exception{return sign(key,header(key.getKeyID()),claims());}
    void reject(WyrmgateJwtVerifier v,String token){
        var e=assertThrows(SecurityFailure.class,()->v.verify(token));
        assertEquals(SecurityCode.AUTHENTICATION_FAILED,e.code());assertEquals("AUTHENTICATION_FAILED",e.getMessage());
    }
    @Test void j01_validRealSignature() throws Exception{
        var v=verifier().verify(valid(current));assertEquals("alice",v.subject());assertEquals(Set.of(AUD),v.audiences());
        assertEquals(TokenKind.ACCESS,v.kind());assertNull(v.authenticatedAt());assertFalse(v.toString().contains("alice"));
    }
    @Test void j02_signatureAndHeaderConfusion() throws Exception{
        var v=verifier();reject(v,sign(other,header("current"),claims()));
        String token=valid(current);String[] parts=token.split("\\.");
        parts[1]=Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":\"tampered\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        reject(v,String.join(".",parts));
        reject(v,sign(current,header("current").type(JOSEObjectType.JWT),claims()));
        reject(v,new PlainJWT(claims().build()).serialize());
        reject(v,sign(current,new JWSHeader.Builder(JWSAlgorithm.RS384).keyID("current").type(new JOSEObjectType("at+jwt")),claims()));
        reject(v,sign(current,header("current").jwk(other.toPublicJWK()),claims()));
        reject(v,sign(current,header("current").jwkURL(java.net.URI.create("https://attacker.example.test/keys")),claims()));
        reject(v,sign(current,header("current").criticalParams(Set.of("custom")).customParam("custom",true),claims()));
        reject(v,"a".repeat(17000));reject(v,null);
    }
    @Test void j03_claimsAndTime() throws Exception{
        var v=verifier();
        List<Consumer<JWTClaimsSet.Builder>> bad=List.of(
            c->c.issuer("https://wrong.example.test"),c->c.audience("asset"),
            c->c.audience(List.of(AUD,"asset")),c->c.claim("client_id","asset"),c->c.claim("client_id",null),
            c->c.subject(null),c->c.issueTime(null),c->c.expirationTime(null),
            c->c.expirationTime(Date.from(NOW)),c->c.issueTime(Date.from(NOW.plusSeconds(1))),
            c->c.expirationTime(Date.from(NOW.plusSeconds(300))),c->c.notBeforeTime(Date.from(NOW.plusSeconds(1))),
            c->c.claim("act",Map.of("sub","delegate")),c->c.claim("cnf",Map.of("jkt","proof")));
        for(var change:bad){var c=claims();change.accept(c);reject(v,sign(current,header("current"),c));}
    }
    @Test void j04_keyTrustAndFreshness() throws Exception{
        var v=verifier();reject(v,valid(other));String token=valid(current);
        for(var invalid:List.of(
            new WyrmgateJwtVerifier.KeySnapshot(ISS,jwks(current,current),NOW),
            new WyrmgateJwtVerifier.KeySnapshot(ISS,new JWKSet(current).toString(false),NOW),
            new WyrmgateJwtVerifier.KeySnapshot(ISS,jwks(current),NOW.minusSeconds(600)),
            new WyrmgateJwtVerifier.KeySnapshot(ISS,jwks(current),NOW.plusSeconds(1)),
            new WyrmgateJwtVerifier.KeySnapshot("https://wrong.example.test",jwks(current),NOW),
            new WyrmgateJwtVerifier.KeySnapshot(ISS,"not-json",NOW))){
            snapshot=invalid;reject(v,token);
        }
        var wrongUse=new RSAKey.Builder(current.toRSAPublicKey()).keyID("current").keyUse(KeyUse.ENCRYPTION).algorithm(JWSAlgorithm.RS256).build();
        snapshot=new WyrmgateJwtVerifier.KeySnapshot(ISS,jwks(wrongUse),NOW);reject(v,token);
        var wrongAlg=new RSAKey.Builder(current.toRSAPublicKey()).keyID("current").keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS384).build();
        snapshot=new WyrmgateJwtVerifier.KeySnapshot(ISS,jwks(wrongAlg),NOW);reject(v,token);
        var weak=new RSAKey.Builder(new com.nimbusds.jose.util.Base64URL("AQ"),new com.nimbusds.jose.util.Base64URL("AQAB")).keyID("current").keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build();
        snapshot=new WyrmgateJwtVerifier.KeySnapshot(ISS,jwks(weak),NOW);reject(v,token);
        snapshot=null;unavailable=true;reject(v,token);
    }
    @Test void j05_rotationOverlapThenRemoval() throws Exception{
        var v=verifier();String old=valid(previous),next=valid(current);
        snapshot=new WyrmgateJwtVerifier.KeySnapshot(ISS,jwks(current,previous),NOW);
        assertEquals("alice",v.verify(old).subject());assertEquals("alice",v.verify(next).subject());
        snapshot=new WyrmgateJwtVerifier.KeySnapshot(ISS,jwks(current),NOW);
        reject(v,old);assertEquals("alice",v.verify(next).subject());
    }
    @Test void j06_realTokenThroughKernelAndApplication() throws Exception{
        var fixture=new SecuredFilesTest();
        var facade=fixture.facade(verifier());
        String bad=sign(other,header("current"),claims());
        assertThrows(SecurityFailure.class,()->facade.initiate(bad,SecuredFilesTest.A,null,SecuredFilesTest.command()));
        assertTrue(fixture.db.rows.isEmpty());
        var result=facade.initiate(valid(current),SecuredFilesTest.A,null,SecuredFilesTest.command());
        assertEquals(1,fixture.db.rows.size());assertEquals("file-storage",result.value().file().storageService());
        assertEquals("actor-a",fixture.db.rows.getFirst().request().caller().actor());
    }
    @Test void stagingRs256ReadinessRequiresFreshUsablePublicKeys()throws Exception{
        var v=WyrmgateJwtVerifier.rs256(new Trust(ISS,AUD),()->{
            if(unavailable)throw new IllegalStateException("fixture unavailable");
            return snapshot==null?new WyrmgateJwtVerifier.KeySnapshot(ISS,jwks(current),NOW):snapshot;
        },Clock.fixed(NOW,ZoneOffset.UTC),Duration.ofMinutes(5),Duration.ofMinutes(10));
        assertTrue(v.ready());assertEquals("alice",v.verify(valid(current)).subject());
        for(var invalid:List.of(
            new WyrmgateJwtVerifier.KeySnapshot(ISS,jwks(current,current),NOW),
            new WyrmgateJwtVerifier.KeySnapshot(ISS,new JWKSet(current).toString(false),NOW),
            new WyrmgateJwtVerifier.KeySnapshot(ISS,jwks(current),NOW.minusSeconds(600)),
            new WyrmgateJwtVerifier.KeySnapshot(ISS,jwks(current),NOW.plusSeconds(1)),
            new WyrmgateJwtVerifier.KeySnapshot("https://wrong.example.test",jwks(current),NOW),
            new WyrmgateJwtVerifier.KeySnapshot(ISS,"{\"keys\":[]}",NOW))){
            snapshot=invalid;assertFalse(v.ready());reject(v,valid(current));
        }
        snapshot=new WyrmgateJwtVerifier.KeySnapshot(ISS,jwks(current,previous),NOW);
        assertTrue(v.ready());assertEquals("alice",v.verify(valid(previous)).subject());
        unavailable=true;assertFalse(v.ready());reject(v,valid(current));
    }
}
