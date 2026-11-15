package io.vyrnforge.filestorage.runtime;

import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.filestorage.runtime.transfer.*;
import io.vyrnforge.filestorage.runtime.transfer.TransferInstruction.Action;
import io.vyrnforge.filestorage.runtime.transfer.TransferFailure.Code;
import io.vyrnforge.filestorage.spi.StorageProvider;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic authority and provider fixture; real HTTP and streaming resource ownership. */
class TransferGatewayTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final TenantId TENANT = new TenantId(UUID.fromString("01900000-0000-7000-8000-000000000001"));
    private static final BackendId BACKEND = new BackendId(UUID.randomUUID());
    private static final Locator OBJECT = new Locator(new LocationId(UUID.randomUUID()), "private-prefix/private-object", null);
    private static final byte[] BYTES = "fixture file bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final String UPLOAD = "/transfer/v1/upload", DOWNLOAD = "/transfer/v1/download";
    private static final String ACTOR = "fixture-proof";

    private static TransferInstruction instruction(Action action, long size, Instant expiry) {
        return new TransferInstruction(TENANT, "fixture-actor", "asset", action, new FileId("logical-file"),
                new VersionId(UUID.randomUUID()), new SessionId(UUID.randomUUID()), BACKEND, 4, OBJECT, size, expiry);
    }

    private static final class Fixture implements TransferAuthority, TransferProviderResolver {
        final Map<String, TransferInstruction> handles = new ConcurrentHashMap<>();
        final AtomicInteger leases = new AtomicInteger(), closes = new AtomicInteger(), streamCloses = new AtomicInteger();
        volatile boolean allowed = true;
        volatile byte[] object;
        volatile CountDownLatch entered, release;
        volatile TransferInstruction last;
        final StorageProvider provider = new StorageProvider() {
            @Override public Set<Capability> capabilities() { return Set.of(); }
            @Override public Metadata put(Locator locator, InputStream bytes, long size, Call call) {
                assertEquals(OBJECT, locator);
                try {
                    if (entered != null) {
                        entered.countDown();
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("fixture deadline");
                    }
                    byte[] content = bytes.readNBytes((int) size + 1);
                    if (content.length != size) throw new StorageFailure(ErrorCode.INVALID);
                    synchronized (Fixture.this) {
                        if (object != null) throw new StorageFailure(ErrorCode.CONFLICT);
                        object = content;
                    }
                    return new Metadata(content.length, null, "application/octet-stream");
                } catch (IOException | InterruptedException ex) { throw new StorageFailure(ErrorCode.TRANSIENT); }
            }
            @Override public InputStream get(Locator locator, Call call) {
                assertEquals(OBJECT, locator);
                if (object == null) throw new StorageFailure(ErrorCode.NOT_FOUND);
                return new ByteArrayInputStream(object) {
                    @Override public void close() { streamCloses.incrementAndGet(); }
                };
            }
            @Override public Metadata head(Locator locator, Call call) { throw new AssertionError("unexpected HEAD"); }
            @Override public void delete(Locator locator, Call call) { throw new AssertionError("unexpected delete"); }
            @Override public Page list(LocationId location, String prefix, String cursor, int limit, Call call) {
                throw new AssertionError("unexpected list");
            }
        };

        String issue(TransferInstruction value) {
            String token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
            handles.put(token, value);
            return token;
        }
        @Override public TransferInstruction claim(String bearer, String handle, Action action) {
            if (!allowed || !ACTOR.equals(bearer)) throw new IllegalStateException("private authority detail");
            // Test-only atomic claim. Real durable control-plane claim is a later increment.
            synchronized (this) {
                var candidate = handles.get(handle);
                if (candidate == null || candidate.action() != action || !NOW.isBefore(candidate.expiresAt()))
                    throw new IllegalStateException("private handle detail");
                handles.remove(handle);
                return candidate;
            }
        }
        @Override public Lease resolve(TransferInstruction value) {
            assertEquals(BACKEND, value.backend());
            assertEquals(4, value.revision());
            assertEquals(OBJECT, value.locator());
            last = value;
            leases.incrementAndGet();
            return new Lease() {
                @Override public StorageProvider provider() { return provider; }
                @Override public void close() { closes.incrementAndGet(); }
            };
        }
        PrivateTransferGateway gateway() {
            return new PrivateTransferGateway(this, this, new TransferLimits(65536, 1, 1024, Duration.ofSeconds(10)), CLOCK);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(TransferHttpConfiguration.class)
    static class TestConfiguration {
        @Bean Fixture fixture() { return new Fixture(); }
        @Bean PrivateTransferGateway gateway(Fixture fixture) { return fixture.gateway(); }
    }

    private static ConfigurableApplicationContext application(String role) {
        return RuntimeApplication.application(TestConfiguration.class).run("--server.port=0", "--fs.role="+role);
    }

    private static HttpResponse<byte[]> call(ConfigurableApplicationContext ctx, String method, String path,
            String bearer, String handle, byte[] body, boolean range) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+
                ctx.getEnvironment().getProperty("local.server.port")+path)).timeout(Duration.ofSeconds(10));
        if (bearer != null) request.header("Authorization", "Bearer "+bearer);
        if (handle != null) request.header("X-Transfer-Handle", handle);
        if (body != null) request.header("Content-Type", "application/octet-stream");
        if (range) request.header("Range", "bytes=0-1");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        }
    }

    private static void status(int expected, HttpResponse<byte[]> response) {
        String body = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(expected, response.statusCode(), body);
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().firstValue("Location").isEmpty());
        for (String secret : List.of("private-prefix", "private-object", "private authority detail", ACTOR))
            assertFalse(body.contains(secret), body);
    }

    @Test void transferRoleStreamsBytesAndDeniesReplayAndWrongIdentity() throws Exception {
        try (var ctx = application("TRANSFER")) {
            var fixture = ctx.getBean(Fixture.class);
            String upload = fixture.issue(instruction(Action.UPLOAD, BYTES.length, NOW.plusSeconds(60)));
            status(403, call(ctx, "PUT", UPLOAD, "wrong", upload, BYTES, false));
            assertEquals(0, fixture.leases.get());
            status(204, call(ctx, "PUT", UPLOAD, ACTOR, upload, BYTES, false));
            assertArrayEquals(BYTES, fixture.object);
            status(403, call(ctx, "PUT", UPLOAD, ACTOR, upload, BYTES, false));
            String download = fixture.issue(instruction(Action.DOWNLOAD, BYTES.length, NOW.plusSeconds(60)));
            status(400, call(ctx, "GET", DOWNLOAD, ACTOR, download, null, true));
            status(400, call(ctx, "HEAD", DOWNLOAD, ACTOR, download, null, false));
            var response = call(ctx, "GET", DOWNLOAD, ACTOR, download, null, false);
            status(200, response);
            assertArrayEquals(BYTES, response.body());
            assertEquals("application/octet-stream", response.headers().firstValue("Content-Type").orElseThrow());
            status(403, call(ctx, "GET", DOWNLOAD, ACTOR, download, null, false));
            assertEquals(2, fixture.leases.get());
            assertEquals(2, fixture.closes.get());
            assertEquals(1, fixture.streamCloses.get());
            assertEquals(404, call(ctx, "GET", "/v1/files/logical-file", ACTOR, null, null, false).statusCode());
        }
    }

    @Test void invalidExpiredRevokedOversizedAndWrongActionNeverResolveProvider() throws Exception {
        try (var ctx = application("TRANSFER")) {
            var fixture = ctx.getBean(Fixture.class);
            String expired = fixture.issue(instruction(Action.UPLOAD, BYTES.length, NOW));
            status(403, call(ctx, "PUT", UPLOAD, ACTOR, expired, BYTES, false));
            String valid = fixture.issue(instruction(Action.UPLOAD, BYTES.length, NOW.plusSeconds(60)));
            status(403, call(ctx, "GET", DOWNLOAD, ACTOR, valid, null, false));
            fixture.allowed = false;
            status(403, call(ctx, "PUT", UPLOAD, ACTOR, valid, BYTES, false));
            fixture.allowed = true;
            status(413, call(ctx, "PUT", UPLOAD, ACTOR, valid, new byte[65537], false));
            assertEquals(0, fixture.leases.get());
            status(204, call(ctx, "PUT", UPLOAD, ACTOR, valid, BYTES, false));
            String wrongLength = fixture.issue(instruction(Action.UPLOAD, BYTES.length, NOW.plusSeconds(60)));
            status(400, call(ctx, "PUT", UPLOAD, ACTOR, wrongLength, new byte[1], false));
            assertEquals(2, fixture.closes.get());
        }
    }

    @Test void concurrentAdmissionAndFailedStreamReleaseResources() throws Exception {
        var fixture = new Fixture();
        var gateway = fixture.gateway();
        fixture.entered = new CountDownLatch(1);
        fixture.release = new CountDownLatch(1);
        String first = fixture.issue(instruction(Action.UPLOAD, BYTES.length, NOW.plusSeconds(60)));
        String second = fixture.issue(instruction(Action.DOWNLOAD, BYTES.length, NOW.plusSeconds(60)));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> gateway.upload(ACTOR, first, BYTES.length, new ByteArrayInputStream(BYTES)));
            try {
                assertTrue(fixture.entered.await(5, TimeUnit.SECONDS));
                assertEquals(Code.BUSY, assertThrows(TransferFailure.class,
                        () -> gateway.download(ACTOR, second, new ByteArrayOutputStream())).code());
                assertTrue(fixture.handles.containsKey(second), "busy must not consume handle");
            } finally { fixture.release.countDown(); }
            future.get(5, TimeUnit.SECONDS);
        }
        assertEquals(Code.UNAVAILABLE, assertThrows(TransferFailure.class, () -> gateway.download(ACTOR, second,
                new OutputStream() {
                    @Override public void write(int b) throws IOException { throw new IOException("client disconnected"); }
                })).code());
        assertEquals(2, fixture.closes.get());
        assertEquals(1, fixture.streamCloses.get());
        String retry = fixture.issue(instruction(Action.DOWNLOAD, BYTES.length, NOW.plusSeconds(60)));
        var output = new ByteArrayOutputStream();
        gateway.download(ACTOR, retry, output);
        assertArrayEquals(BYTES, output.toByteArray());
        assertEquals(3, fixture.closes.get());
    }

    @Test void exactSizeAndRoleIsolation() throws Exception {
        var fixture = new Fixture();
        var gateway = fixture.gateway();
        String shortInput = fixture.issue(instruction(Action.UPLOAD, BYTES.length, NOW.plusSeconds(60)));
        assertEquals(Code.INVALID, assertThrows(TransferFailure.class, () ->
                gateway.upload(ACTOR, shortInput, -1, new ByteArrayInputStream(new byte[1]))).code());
        assertNull(fixture.object);
        fixture.object = BYTES;
        String wrongSize = fixture.issue(instruction(Action.DOWNLOAD, BYTES.length-1, NOW.plusSeconds(60)));
        var output = new ByteArrayOutputStream();
        assertEquals(Code.UNAVAILABLE, assertThrows(TransferFailure.class, () -> gateway.download(ACTOR, wrongSize, output)).code());
        assertEquals(0, output.size(), "withhold final chunk on extra bytes");
        assertEquals(2, fixture.closes.get());
        assertEquals(1, fixture.streamCloses.get());
        for (String role : List.of("API", "WORKER")) assertThrows(RuntimeException.class, () -> {
            try (var ignored = application(role)) { fail("transfer routes not allowed"); }
        });
        try (var ctx = RuntimeApplication.start("--server.port=0", "--fs.role=TRANSFER")) {
            assertEquals(404, call(ctx, "GET", DOWNLOAD, ACTOR, null, null, false).statusCode());
        }
    }
}
