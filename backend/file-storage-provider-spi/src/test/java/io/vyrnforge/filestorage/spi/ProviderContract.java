package io.vyrnforge.filestorage.spi;

import io.vyrnforge.filestorage.domain.StorageModel.LocationId;
import io.vyrnforge.filestorage.domain.StorageModel.Locator;
import io.vyrnforge.filestorage.spi.StorageProvider.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Reusable minimum acceptance suite. Extend with a real isolated provider profile,
 * two configured locations, and explicit capability/profile tests.
 * No production credentials or objects. Each test owns a unique synthetic prefix.
 */
public abstract class ProviderContract {
    private final String prefix = "contract/" + UUID.randomUUID() + "/";
    private final List<Locator> cleanup = new ArrayList<>();
    protected abstract StorageProvider provider();
    protected abstract LocationId location();
    protected abstract LocationId otherLocation();

    protected Call call() {
        return new Call(Duration.ofSeconds(10));
    }

    private Locator object(LocationId location, String name) {
        Locator locator = new Locator(location, prefix + name, null);
        cleanup.add(locator);
        return locator;
    }

    @AfterEach
    protected void cleanupContractObjects() {
        for (Locator locator : cleanup) {
            provider().delete(locator, call());
        }
    }

    private Metadata put(Locator locator, byte[] bytes) {
        return provider().put(locator, new ByteArrayInputStream(bytes), bytes.length, call());
    }

    private byte[] read(Locator locator) throws IOException {
        try (var stream = provider().get(locator, call())) {
            return stream.readAllBytes();
        }
    }

    private static void fails(ErrorCode code, org.junit.jupiter.api.function.Executable work) {
        StorageFailure failure = assertThrows(StorageFailure.class, work);
        assertEquals(code, failure.code());
        assertEquals(code.name(), failure.getMessage());
    }

    @Test
    public void exactBytesHeadAndStreamOwnership() throws Exception {
        Locator locator = object(location(), "bytes");
        byte[] bytes = {0, 1, 2, -1};
        var input = new ByteArrayInputStream(bytes) {
            @Override
            public void close() {
                fail("adapter must not close caller-owned input");
            }
        };
        assertEquals(bytes.length, provider().put(locator, input, bytes.length, call()).size());
        Metadata head = provider().head(locator, call());
        assertEquals(bytes.length, head.size());
        assertFalse(head.mimeType().isBlank());
        if (head.sha256() != null) {
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), head.sha256());
        }
        assertArrayEquals(bytes, read(locator));
        try (var stream = provider().get(locator, call())) {
            assertEquals(0, stream.read()); // early close must release provider resources
        }
        assertArrayEquals(bytes, read(locator));
        Locator empty = object(location(), "empty");
        assertEquals(0, put(empty, new byte[0]).size());
        assertArrayEquals(new byte[0], read(empty));
    }

    @Test
    public void wrongLengthAndOverwriteNeverCommitReplacement() throws IOException {
        Locator shortObject = object(location(), "short");
        Locator longObject = object(location(), "long");
        fails(ErrorCode.INVALID, () -> provider().put(shortObject, new ByteArrayInputStream(new byte[]{1}), 2, call()));
        fails(ErrorCode.INVALID, () -> provider().put(longObject, new ByteArrayInputStream(new byte[]{1, 2}), 1, call()));
        fails(ErrorCode.NOT_FOUND, () -> provider().head(shortObject, call()));
        fails(ErrorCode.NOT_FOUND, () -> provider().head(longObject, call()));
        Locator stable = object(location(), "stable");
        put(stable, new byte[]{7});
        fails(ErrorCode.CONFLICT, () -> put(stable, new byte[]{8}));
        assertArrayEquals(new byte[]{7}, read(stable));
    }

    @Test
    public void concurrentCreateOnlyHasOneWinner() throws Exception {
        Locator locator = object(location(), "race");
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Object> first = () -> race(locator, (byte) 1, barrier);
            Callable<Object> second = () -> race(locator, (byte) 2, barrier);
            var a = executor.submit(first);
            var b = executor.submit(second);
            var results = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(Byte.class::isInstance).count());
            assertEquals(1, results.stream().filter(r -> r == ErrorCode.CONFLICT).count());
            byte winner = (Byte) results.stream().filter(Byte.class::isInstance).findFirst().orElseThrow();
            assertArrayEquals(new byte[]{winner}, read(locator));
        }
    }

    private Object race(Locator locator, byte value, CyclicBarrier barrier) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        try {
            put(locator, new byte[]{value});
            return value;
        } catch (StorageFailure failure) {
            return failure.code();
        }
    }

    @Test
    public void listingIsBoundedLocationAndPrefixScoped() {
        assertNotEquals(location(), otherLocation());
        Locator a = object(location(), "listed/a");
        Locator b = object(location(), "listed/b");
        Locator c = object(location(), "listed/c");
        for (Locator locator : List.of(a, b, c, object(location(), "outside"),
                object(otherLocation(), "listed/a"))) {
            put(locator, new byte[]{1});
        }
        Set<Locator> seen = new HashSet<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            Page page = provider().list(location(), prefix + "listed/", cursor, 1, call());
            assertTrue(page.objects().size() <= 1);
            for (Locator locator : page.objects()) {
                assertTrue(seen.add(locator), "duplicate list result");
            }
            cursor = page.nextCursor();
            if (cursor != null) assertTrue(cursors.add(cursor), "repeated continuation");
            assertTrue(++pages <= 10, "listing did not terminate");
        } while (cursor != null);
        assertEquals(Set.of(a, b, c), seen);
        fails(ErrorCode.INVALID, () -> provider().list(location(), prefix, null, 0, call()));
    }

    @Test
    public void deletionAndMissingReadAreExplicit() {
        Locator locator = object(location(), "delete");
        fails(ErrorCode.NOT_FOUND, () -> provider().get(locator, call()));
        fails(ErrorCode.NOT_FOUND, () -> provider().head(locator, call()));
        provider().delete(locator, call());
        put(locator, new byte[]{1});
        provider().delete(locator, call());
        provider().delete(locator, call());
        fails(ErrorCode.NOT_FOUND, () -> provider().head(locator, call()));
        fails(ErrorCode.NOT_FOUND, () -> provider().get(locator, call()));
    }

    @Test
    public void absentVersionNeverFallsBackToLatest() throws IOException {
        Locator locator = object(location(), "version");
        put(locator, new byte[]{1});
        Locator absent = new Locator(locator.location(), locator.objectKey(), "missing-version");
        for (var action : List.<org.junit.jupiter.api.function.Executable>of(
                () -> provider().head(absent, call()), () -> provider().get(absent, call()),
                () -> provider().delete(absent, call()))) {
            var failure = assertThrows(StorageFailure.class, action);
            assertTrue(Set.of(ErrorCode.NOT_FOUND, ErrorCode.UNSUPPORTED, ErrorCode.INVALID).contains(failure.code()));
        }
        assertArrayEquals(new byte[]{1}, read(locator));
    }

    @Test
    public void unadvertisedCapabilitiesFailExplicitly() {
        Locator locator = object(location(), "optional");
        Locator destination = object(location(), "copy");
        Set<Capability> capabilities = provider().capabilities();
        var expiry = Instant.now().plusSeconds(60);
        var upload = new Multipart(locator, "synthetic-handle");
        if (!capabilities.contains(Capability.COPY)) {
            fails(ErrorCode.UNSUPPORTED, () -> provider().copy(locator, destination, call()));
        }
        if (!capabilities.contains(Capability.PRESIGN)) {
            fails(ErrorCode.UNSUPPORTED, () -> provider().signUpload(locator, 1, expiry, call()));
            fails(ErrorCode.UNSUPPORTED, () -> provider().signDownload(locator, expiry, call()));
        }
        if (!capabilities.contains(Capability.MULTIPART)) {
            fails(ErrorCode.UNSUPPORTED, () -> provider().beginMultipart(locator, call()));
            fails(ErrorCode.UNSUPPORTED, () -> provider().putPart(upload, 1, new ByteArrayInputStream(new byte[]{1}), 1, call()));
            fails(ErrorCode.UNSUPPORTED, () -> provider().completeMultipart(upload, List.of(new Part(1, "synthetic")), call()));
            fails(ErrorCode.UNSUPPORTED, () -> provider().abortMultipart(upload, call()));
        }
    }
}
