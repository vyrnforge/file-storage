package io.vyrnforge.filestorage.s3;

import io.vyrnforge.filestorage.domain.StorageModel.BackendId;
import io.vyrnforge.filestorage.domain.StorageModel.Location;
import io.vyrnforge.filestorage.domain.StorageModel.LocationId;
import io.vyrnforge.filestorage.domain.StorageModel.Locator;
import io.vyrnforge.filestorage.spi.ProviderContract;
import io.vyrnforge.filestorage.spi.StorageProvider;
import io.vyrnforge.filestorage.spi.StorageProvider.Call;
import io.vyrnforge.filestorage.spi.StorageProvider.ErrorCode;
import io.vyrnforge.filestorage.spi.StorageProvider.StorageFailure;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import static org.junit.jupiter.api.Assertions.*;

/** Real disposable compatibility target, never a configured external endpoint. */
class S3StorageProviderTest extends ProviderContract {
    private static final String USER = "test-" + UUID.randomUUID();
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final GenericContainer<?> SERVER = new GenericContainer<>(
            DockerImageName.parse("docker.io/minio/minio@sha256:a1ea29fa28355559ef137d71fc570e508a214ec84ff8083e39bc5428980b015e"))
            .withEnv("MINIO_ROOT_USER", USER)
            .withEnv("MINIO_ROOT_PASSWORD", PASSWORD)
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000).withStartupTimeout(Duration.ofMinutes(2)));
    private static final LocationId A = new LocationId(UUID.randomUUID());
    private static final LocationId B = new LocationId(UUID.randomUUID());
    private static final BackendId BACKEND = new BackendId(UUID.randomUUID());
    private static final Map<LocationId, Location> LOCATIONS = Map.of(
            A, new Location(A, BACKEND, "contract-a", ""),
            B, new Location(B, BACKEND, "contract-b", ""));
    private static final Call VALIDATION = new Call(Duration.ofSeconds(10));
    @TempDir
    static Path spool;
    private static S3StorageProvider adapter;

    private static S3Client client(String user, String password) {
        return S3Client.builder()
                .endpointOverride(URI.create("http://" + SERVER.getHost() + ":" + SERVER.getMappedPort(9000)))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(user, password)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClientBuilder(ApacheHttpClient.builder().connectionTimeout(Duration.ofSeconds(2))
                        .socketTimeout(Duration.ofSeconds(3)).connectionAcquisitionTimeout(Duration.ofSeconds(2))
                        .maxConnections(8))
                .overrideConfiguration(config -> config.retryPolicy(RetryPolicy.none())
                        .apiCallTimeout(Duration.ofSeconds(15)))
                .build();
    }

    @BeforeAll
    static void start() {
        SERVER.start();
        S3Client client = client(USER, PASSWORD);
        for (Location location : LOCATIONS.values()) {
            client.createBucket(request -> request.bucket(location.bucket()));
        }
        adapter = new S3StorageProvider(client, LOCATIONS, spool, 1024 * 1024,
                4, Duration.ofSeconds(15), 128, Duration.ofMinutes(1));
        adapter.validateUnversioned(VALIDATION);
    }

    @AfterAll
    static void stop() {
        if (adapter != null) adapter.close();
        SERVER.stop();
    }

    @Override protected StorageProvider provider() { return adapter; }
    @Override protected LocationId location() { return A; }
    @Override protected LocationId otherLocation() { return B; }

    private static void fails(ErrorCode code, org.junit.jupiter.api.function.Executable work) {
        StorageFailure ex = assertThrows(StorageFailure.class, work);
        assertEquals(code, ex.code());
        assertEquals(code.name(), ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    void boundedSpoolAndDeadlineCleanUpWithoutWriting() throws Exception {
        Locator target = new Locator(A, "profile/" + UUID.randomUUID(), null);
        fails(ErrorCode.INVALID, () -> adapter.put(target, new ByteArrayInputStream(new byte[0]),
                1024 * 1024 + 1, call()));
        var slow = new ByteArrayInputStream(new byte[]{1}) {
            @Override public int read(byte[] bytes, int offset, int length) {
                try { Thread.sleep(40); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                return super.read(bytes, offset, length);
            }
        };
        fails(ErrorCode.TIMEOUT, () -> adapter.put(target, slow, 1, new Call(Duration.ofMillis(10))));
        fails(ErrorCode.NOT_FOUND, () -> adapter.head(target, call()));
        try (var entries = Files.list(spool)) {
            assertEquals(0, entries.count());
        }
    }

    @Test
    void exactNamespaceAndUnknownLocationFailClosed() {
        LocationId scoped = new LocationId(UUID.randomUUID());
        try (var scopedAdapter = new S3StorageProvider(client(USER, PASSWORD),
                Map.of(scoped, new Location(scoped, BACKEND, "contract-a", "isolated")), spool,
                1024, 1, Duration.ofSeconds(15), 8, Duration.ofMinutes(1))) {
            scopedAdapter.validateUnversioned(VALIDATION);
            fails(ErrorCode.INVALID, () -> scopedAdapter.head(new Locator(scoped, "other/object", null), call()));
            fails(ErrorCode.INVALID, () -> scopedAdapter.list(scoped, "", null, 10, call()));
            fails(ErrorCode.INVALID, () -> scopedAdapter.head(new Locator(A, "isolated/object", null), call()));
        }
    }

    @Test
    void mixedBackendAndOverlappingLocationsRejectedBeforeIo() {
        LocationId left = new LocationId(UUID.randomUUID());
        LocationId right = new LocationId(UUID.randomUUID());
        try (S3Client sdk = client(USER, PASSWORD)) {
            fails(ErrorCode.INVALID, () -> new S3StorageProvider(sdk, Map.of(
                    left, new Location(left, BACKEND, "contract-a", "area"),
                    right, new Location(right, BACKEND, "contract-a", "area/nested")), spool,
                    1024, 1, Duration.ofSeconds(15), 8, Duration.ofMinutes(1)));
            fails(ErrorCode.INVALID, () -> new S3StorageProvider(sdk, Map.of(
                    left, new Location(left, BACKEND, "contract-a", "area"),
                    right, new Location(right, new BackendId(UUID.randomUUID()), "contract-b", "other")), spool,
                    1024, 1, Duration.ofSeconds(15), 8, Duration.ofMinutes(1)));
        }
    }

    @Test
    void permissionFailureAndVersioningAreRejected() {
        try (var denied = new S3StorageProvider(client("invalid-" + UUID.randomUUID(), PASSWORD),
                LOCATIONS, spool, 1024, 1, Duration.ofSeconds(15), 8, Duration.ofMinutes(1))) {
            fails(ErrorCode.FORBIDDEN, () -> denied.validateUnversioned(VALIDATION));
        }
        LocationId id = new LocationId(UUID.randomUUID());
        String bucket = "versioned-profile";
        try (S3Client admin = client(USER, PASSWORD)) {
            admin.createBucket(req -> req.bucket(bucket));
            admin.putBucketVersioning(req -> req.bucket(bucket).versioningConfiguration(
                    config -> config.status("Enabled")));
        }
        try (var versioned = new S3StorageProvider(client(USER, PASSWORD),
                Map.of(id, new Location(id, BACKEND, bucket, "")), spool,
                1024, 1, Duration.ofSeconds(15), 8, Duration.ofMinutes(1))) {
            fails(ErrorCode.UNSUPPORTED, () -> versioned.validateUnversioned(VALIDATION));
            fails(ErrorCode.UNSUPPORTED, () -> versioned.delete(new Locator(id, "object", null), call()));
        }
    }

    @Test
    void returnedStreamDeadlineAbortsAndReleasesConnection() throws Exception {
        Locator target = new Locator(A, "profile/" + UUID.randomUUID(), null);
        try {
            adapter.put(target, new ByteArrayInputStream(new byte[]{1, 2, 3}), 3, call());
            try (var stream = adapter.get(target, new Call(Duration.ofSeconds(1)))) {
                Thread.sleep(1100);
                fails(ErrorCode.TIMEOUT, stream::read);
            }
            try (var next = adapter.get(target, call())) {
                assertArrayEquals(new byte[]{1, 2, 3}, next.readAllBytes());
            }
        } finally {
            adapter.delete(target, call());
        }
    }
}
