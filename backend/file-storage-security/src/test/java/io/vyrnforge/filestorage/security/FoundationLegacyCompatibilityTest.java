package io.vyrnforge.filestorage.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import static org.junit.jupiter.api.Assertions.*;

// G1 test only; verifies current consumer boundary, not new-profile implementation.
class FoundationLegacyCompatibilityTest {
    @BeforeAll static void keys() throws Exception { WyrmgateJwtVerifierTest.generate(); }

    @Test void currentLegacyProfileAcceptsDedicatedClientButRejectsNewResourceProfile() throws Exception {
        var fixture=new WyrmgateJwtVerifierTest();
        var verifier=fixture.verifier();
        var key=WyrmgateJwtVerifierTest.current;
        assertNotNull(verifier.verify(WyrmgateJwtVerifierTest.valid(key)));
        var newProfile=WyrmgateJwtVerifierTest.claims()
            .audience("https://storage.example.invalid/api")
            .claim("client_id","asset-backend-t1").jwtID("synthetic-id");
        fixture.reject(verifier,WyrmgateJwtVerifierTest.sign(key,WyrmgateJwtVerifierTest.header("current"),newProfile));
        var wrongClient=WyrmgateJwtVerifierTest.claims().claim("client_id","asset-backend-t1");
        fixture.reject(verifier,WyrmgateJwtVerifierTest.sign(key,WyrmgateJwtVerifierTest.header("current"),wrongClient));
    }
}
