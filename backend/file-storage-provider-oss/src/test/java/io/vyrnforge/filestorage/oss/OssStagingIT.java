package io.vyrnforge.filestorage.oss;

import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.filestorage.spi.ProviderContract;
import io.vyrnforge.filestorage.spi.StorageProvider;
import io.vyrnforge.filestorage.spi.StorageProvider.Call;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;

/**
 * Explicitly invoked staging acceptance; never selected by normal Surefire
 * discovery. Requires two dedicated disposable unversioned OSS buckets.
 * No bucket creation, deletion, version changes or production access.
 */
public class OssStagingIT extends ProviderContract {
    private static final LocationId A = new LocationId(UUID.randomUUID());
    private static final LocationId B = new LocationId(UUID.randomUUID());
    @TempDir static Path spool;
    private static OssStorageProvider adapter;

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing approved staging configuration: " + key);
        }
        return value;
    }

    @BeforeAll
    static void start() {
        if (!"DEDICATED_DISPOSABLE_OSS_BUCKETS".equals(System.getenv("FS_OSS_STAGING_CONFIRM"))) {
            throw new IllegalStateException("Explicit dedicated OSS staging confirmation required");
        }
        String first = required("FS_OSS_STAGING_BUCKET_A");
        String second = required("FS_OSS_STAGING_BUCKET_B");
        if (first.equals(second)) throw new IllegalStateException("Two distinct staging buckets required");
        Target target = new Target(Provider.OSS, URI.create(required("FS_OSS_STAGING_ENDPOINT")),
                required("FS_OSS_STAGING_REGION"), Addressing.VIRTUAL_HOST);
        // Credentials are injected only by the local approved secret workflow.
        // Short-lived STS credentials; never print or persist these values.
        var credentials = new DefaultCredentialProvider(required("FS_OSS_STAGING_ACCESS_KEY_ID"),
                required("FS_OSS_STAGING_ACCESS_KEY_SECRET"), required("FS_OSS_STAGING_SECURITY_TOKEN"));
        var backend = new BackendId(UUID.randomUUID());
        var locations = Map.of(A, new Location(A, backend, first, ""),
                B, new Location(B, backend, second, ""));
        var sdk = OssClientFactory.create(target, credentials, 3000, 3000, 5000, 15000, 8);
        adapter = new OssStorageProvider(sdk, locations, spool, 1024 * 1024, 4,
                Duration.ofSeconds(30), 128, Duration.ofMinutes(2), 8);
        adapter.validateUnversioned(new Call(Duration.ofSeconds(30)));
    }

    @AfterAll
    static void stop() {
        if (adapter != null) adapter.close();
    }

    @Override protected StorageProvider provider() { return adapter; }
    @Override protected LocationId location() { return A; }
    @Override protected LocationId otherLocation() { return B; }
    @Override protected Call call() { return new Call(Duration.ofSeconds(30)); }
}
