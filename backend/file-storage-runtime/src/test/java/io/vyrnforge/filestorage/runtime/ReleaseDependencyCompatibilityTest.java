package io.vyrnforge.filestorage.runtime;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReleaseDependencyCompatibilityTest {
    private static String jar(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).getFileName().toString();
    }

    @Test void patchedCryptoProviderIsResolvedAndCompatibleWithStandardRsa() throws Exception {
        assertEquals("bcprov-jdk18on-1.87.jar", jar(BouncyCastleProvider.class));
        var provider = new BouncyCastleProvider(); // No global provider registration.
        byte[] bytes = "abc".getBytes(StandardCharsets.UTF_8);
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256", provider).digest(bytes)));
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var key = generator.generateKeyPair();
        var signer = Signature.getInstance("SHA256withRSA", provider);
        signer.initSign(key.getPrivate());
        signer.update(bytes);
        byte[] signed = signer.sign();
        var verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(key.getPublic());
        verifier.update(bytes);
        assertTrue(verifier.verify(signed));
        verifier.initVerify(key.getPublic());
        verifier.update("changed".getBytes(StandardCharsets.UTF_8));
        assertFalse(verifier.verify(signed));
    }

    @Test void alignedTelemetryRetainsW3cTraceAndBaggageExtraction() throws Exception {
        assertEquals("opentelemetry-api-1.68.0.jar", jar(W3CTraceContextPropagator.class));
        assertEquals("opentelemetry-context-1.68.0.jar", jar(Context.class));
        var getter = new TextMapGetter<Map<String,String>>() {
            public Iterable<String> keys(Map<String,String> carrier) { return carrier.keySet(); }
            public String get(Map<String,String> carrier,String key) { return carrier.get(key); }
        };
        var headers = Map.of("traceparent", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
                "tracestate", "vendor=example", "baggage", "test=fixture");
        var extracted = W3CTraceContextPropagator.getInstance().extract(Context.root(), headers, getter);
        var span = Span.fromContext(extracted).getSpanContext();
        assertTrue(span.isValid());
        assertTrue(span.isRemote());
        assertEquals("0123456789abcdef0123456789abcdef", span.getTraceId());
        assertEquals("example", span.getTraceState().get("vendor"));
        var baggage = W3CBaggagePropagator.getInstance().extract(extracted, headers, getter);
        assertEquals("fixture", Baggage.fromContext(baggage).getEntryValue("test"));
        assertFalse(Span.fromContext(W3CTraceContextPropagator.getInstance()
                .extract(Context.root(), Map.of("traceparent","invalid"), getter)).getSpanContext().isValid());
    }
}
