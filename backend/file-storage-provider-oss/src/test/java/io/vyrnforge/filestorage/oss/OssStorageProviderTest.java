package io.vyrnforge.filestorage.oss;

import com.aliyun.oss.OSS;
import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.filestorage.spi.ProviderContract;
import io.vyrnforge.filestorage.spi.StorageProvider;
import io.vyrnforge.filestorage.spi.StorageProvider.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real native SDK against a deliberately small loopback OSS wire fixture.
 * This is NOT Alibaba OSS staging or proof of provider-side atomicity.
 */
class OssStorageProviderTest extends ProviderContract {
    private static final BackendId BACKEND = new BackendId(UUID.randomUUID());
    private static final LocationId A = new LocationId(UUID.randomUUID());
    private static final LocationId B = new LocationId(UUID.randomUUID());
    private static final Map<LocationId, Location> LOCATIONS = Map.of(
            A, new Location(A, BACKEND, "contract-a", ""),
            B, new Location(B, BACKEND, "contract-b", ""));
    private static OssWireFixture fixture;
    private static OssStorageProvider adapter;
    @TempDir static Path spool;

    @BeforeAll
    static void start() throws IOException {
        fixture = new OssWireFixture();
        adapter = new OssStorageProvider(fixture.client(), LOCATIONS, spool, 1024 * 1024,
                4, Duration.ofSeconds(15), 128, Duration.ofMinutes(1), 8);
        adapter.validateUnversioned(new Call(Duration.ofSeconds(10)));
    }

    @AfterAll
    static void stop() {
        try { if (adapter != null) adapter.close(); }
        finally { if (fixture != null) fixture.close(); }
    }

    @Override protected StorageProvider provider() { return adapter; }
    @Override protected LocationId location() { return A; }
    @Override protected LocationId otherLocation() { return B; }

    private static void fails(ErrorCode code, org.junit.jupiter.api.function.Executable work) {
        var ex = assertThrows(StorageFailure.class, work);
        assertEquals(code, ex.code());
        assertEquals(code.name(), ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    void unvalidatedAndVersionedBucketsFailClosed() {
        LocationId id = new LocationId(UUID.randomUUID());
        for (String status : java.util.List.of("Enabled", "Suspended", "Unknown")) {
            String bucket = "versioned-" + status.toLowerCase();
            fixture.versioning(bucket, status);
            try (var provider = new OssStorageProvider(fixture.client(), Map.of(id, new Location(id, BACKEND, bucket, "")),
                    spool, 1024, 1, Duration.ofSeconds(10), 8, Duration.ofMinutes(1), 2)) {
                fails(ErrorCode.UNSUPPORTED, () -> provider.head(new Locator(id, "object", null), call()));
                fails(ErrorCode.UNSUPPORTED, () -> provider.validateUnversioned(call()));
                fails(ErrorCode.UNSUPPORTED, () -> provider.delete(new Locator(id, "object", null), call()));
            }
        }
    }

    @Test
    void nativePermissionAndCallerDeadlineAreNeutral() {
        try (var denied = new OssStorageProvider(fixture.deniedClient(), LOCATIONS, spool, 1024, 1,
                Duration.ofSeconds(10), 8, Duration.ofMinutes(1), 2)) {
            fails(ErrorCode.FORBIDDEN, () -> denied.validateUnversioned(call()));
        }
        fails(ErrorCode.TIMEOUT, () -> adapter.head(new Locator(A, "slow-head", null), new Call(Duration.ofMillis(50))));
    }

    @Test
    void uploadBoundsAndStreamDeadline() throws Exception {
        Locator object = new Locator(A, "profile/" + UUID.randomUUID(), null);
        try {
            fails(ErrorCode.INVALID, () -> adapter.put(object, new ByteArrayInputStream(new byte[0]),
                    1024 * 1024 + 1, call()));
            var slow = new ByteArrayInputStream(new byte[]{1}) {
                @Override public int read(byte[] bytes, int offset, int length) {
                    try { Thread.sleep(40); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                    return super.read(bytes, offset, length);
                }
            };
            fails(ErrorCode.TIMEOUT, () -> adapter.put(object, slow, 1, new Call(Duration.ofMillis(10))));
            fails(ErrorCode.NOT_FOUND, () -> adapter.head(object, call()));
            try (var files = Files.list(spool)) { assertEquals(0, files.count()); }
            adapter.put(object, new ByteArrayInputStream(new byte[]{1, 2}), 2, call());
            try (var stream = adapter.get(object, new Call(Duration.ofSeconds(1)))) {
                Thread.sleep(1100);
                fails(ErrorCode.TIMEOUT, stream::read);
            }
            try (var stream = adapter.get(object, call())) {
                assertArrayEquals(new byte[]{1, 2}, stream.readAllBytes());
            }
        } finally {
            adapter.delete(object, call());
        }
    }

    @Test
    void namespaceGuardRejectsOutOfScopeAndOverlaps() {
        LocationId id = new LocationId(UUID.randomUUID());
        try (var scoped = new OssStorageProvider(fixture.client(), Map.of(id, new Location(id, BACKEND, "contract-a", "area")),
                spool, 1024, 1, Duration.ofSeconds(10), 8, Duration.ofMinutes(1), 2)) {
            scoped.validateUnversioned(call());
            fails(ErrorCode.INVALID, () -> scoped.head(new Locator(id, "other/key", null), call()));
            fails(ErrorCode.INVALID, () -> scoped.list(id, "", null, 1, call()));
        }
        OSS sdk = fixture.client();
        try {
            fails(ErrorCode.INVALID, () -> new OssStorageProvider(sdk, Map.of(
                    A, new Location(A, BACKEND, "contract-a", "area"),
                    B, new Location(B, BACKEND, "contract-a", "area/nested")), spool,
                    1024, 1, Duration.ofSeconds(10), 8, Duration.ofMinutes(1), 2));
        } finally { sdk.shutdown(); }
    }
}
