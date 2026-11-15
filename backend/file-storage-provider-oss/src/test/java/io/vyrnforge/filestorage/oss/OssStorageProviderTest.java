package io.vyrnforge.filestorage.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.auth.SignVersion;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.filestorage.spi.ProviderContract;
import io.vyrnforge.filestorage.spi.StorageProvider;
import io.vyrnforge.filestorage.spi.StorageProvider.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
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
    private static final String USER = "synthetic" + UUID.randomUUID().toString().replace("-", "");
    private static final String SECRET = UUID.randomUUID().toString();
    private static final Map<String, byte[]> OBJECTS = new ConcurrentHashMap<>();
    private static final Map<String, String> VERSIONING = new ConcurrentHashMap<>();
    private static HttpServer server;
    private static ExecutorService serverThreads;
    private static OssStorageProvider adapter;
    @TempDir static Path spool;

    private static OSS client(String user) {
        var config = new ClientBuilderConfiguration();
        config.setSignatureVersion(SignVersion.V4);
        config.setSLDEnabled(true);
        config.setSupportCname(false);
        config.setConnectionTimeout(1000);
        config.setConnectionRequestTimeout(1000);
        config.setSocketTimeout(2000);
        config.setRequestTimeoutEnabled(true);
        config.setRequestTimeout(3000);
        config.setMaxConnections(8);
        config.setMaxErrorRetry(0);
        config.setCrcCheckEnabled(false); // fixture does not implement CRC; real profile must test it
        config.setRedirectEnable(false);
        return OSSClientBuilder.create().endpoint("http://127.0.0.1:" + server.getAddress().getPort())
                .region("cn-hangzhou").credentialsProvider(new DefaultCredentialProvider(user, SECRET))
                .clientConfiguration(config).build();
    }

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverThreads = Executors.newFixedThreadPool(8);
        server.setExecutor(serverThreads);
        server.createContext("/", OssStorageProviderTest::handle);
        server.start();
        adapter = new OssStorageProvider(client(USER), LOCATIONS, spool, 1024 * 1024,
                4, Duration.ofSeconds(15), 128, Duration.ofMinutes(1), 8);
        adapter.validateUnversioned(new Call(Duration.ofSeconds(10)));
    }

    @AfterAll
    static void stop() {
        if (adapter != null) adapter.close();
        if (server != null) server.stop(0);
        if (serverThreads != null) serverThreads.shutdownNow();
    }

    @Override protected StorageProvider provider() { return adapter; }
    @Override protected LocationId location() { return A; }
    @Override protected LocationId otherLocation() { return B; }

    private static void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            var headers = exchange.getResponseHeaders();
            headers.set("x-oss-request-id", "synthetic-request");
            headers.set("Last-Modified", "Thu, 01 Jan 2026 00:00:00 GMT");
            headers.set("ETag", "\"d41d8cd98f00b204e9800998ecf8427e\"");
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.startsWith("OSS4-HMAC-SHA256 ") || !auth.contains(USER)) {
                error(exchange, 403, "AccessDenied");
                return;
            }
            String path = exchange.getRequestURI().getPath().substring(1);
            int slash = path.indexOf('/');
            String bucket = slash < 0 ? path : path.substring(0, slash);
            String key = slash < 0 ? "" : path.substring(slash + 1);
            String identity = bucket + "/" + key;
            Map<String, String> query = new java.util.HashMap<>();
            String raw = exchange.getRequestURI().getRawQuery();
            if (raw != null) for (String pair : raw.split("&")) {
                String[] parts = pair.split("=", 2);
                query.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                        parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
            }
            if (query.containsKey("versioning")) {
                String status = VERSIONING.getOrDefault(bucket, "");
                xml(exchange, 200, "<VersioningConfiguration>" +
                        (status.isEmpty() ? "" : "<Status>" + status + "</Status>") + "</VersioningConfiguration>");
                return;
            }
            if (query.containsKey("list-type")) {
                String prefix = query.getOrDefault("prefix", "");
                String after = query.getOrDefault("continuation-token", "");
                int limit = Integer.parseInt(query.getOrDefault("max-keys", "100"));
                var all = OBJECTS.keySet().stream().filter(k -> k.startsWith(bucket + "/"))
                        .map(k -> k.substring(bucket.length() + 1))
                        .filter(k -> k.startsWith(prefix) && k.compareTo(after) > 0).sorted().toList();
                var page = all.stream().limit(limit).toList();
                boolean more = all.size() > page.size();
                StringBuilder body = new StringBuilder("<ListBucketResult><Name>").append(bucket)
                        .append("</Name><Prefix>").append(prefix).append("</Prefix><MaxKeys>").append(limit)
                        .append("</MaxKeys><KeyCount>").append(page.size()).append("</KeyCount><IsTruncated>")
                        .append(more).append("</IsTruncated>");
                for (String item : page) body.append("<Contents><Key>").append(item)
                        .append("</Key><LastModified>2026-01-01T00:00:00.000Z</LastModified>")
                        .append("<ETag>d41d8cd98f00b204e9800998ecf8427e</ETag><Size>")
                        .append(OBJECTS.get(bucket + "/" + item).length)
                        .append("</Size><StorageClass>Standard</StorageClass></Contents>");
                if (more) body.append("<NextContinuationToken>").append(page.getLast()).append("</NextContinuationToken>");
                xml(exchange, 200, body.append("</ListBucketResult>").toString());
                return;
            }
            switch (exchange.getRequestMethod()) {
                case "PUT" -> {
                    byte[] bytes = exchange.getRequestBody().readAllBytes();
                    if (!"true".equals(exchange.getRequestHeaders().getFirst("x-oss-forbid-overwrite"))
                            || !Integer.toString(bytes.length).equals(exchange.getRequestHeaders().getFirst("Content-Length"))) {
                        error(exchange, 400, "InvalidArgument");
                    } else if (OBJECTS.putIfAbsent(identity, bytes) != null) {
                        error(exchange, 409, "FileAlreadyExists");
                    } else {
                        exchange.sendResponseHeaders(200, -1);
                    }
                }
                case "GET", "HEAD" -> {
                    if (key.equals("slow-head")) {
                        try { Thread.sleep(700); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                    }
                    byte[] bytes = OBJECTS.get(identity);
                    if (bytes == null) {
                        error(exchange, 404, "NoSuchKey");
                    } else {
                        headers.set("Content-Type", "application/octet-stream");
                        headers.set("Content-Length", Integer.toString(bytes.length));
                        boolean head = exchange.getRequestMethod().equals("HEAD");
                        exchange.sendResponseHeaders(200, head || bytes.length == 0 ? -1 : bytes.length);
                        if (!head && bytes.length != 0) exchange.getResponseBody().write(bytes);
                    }
                }
                case "DELETE" -> {
                    OBJECTS.remove(identity);
                    exchange.sendResponseHeaders(204, -1);
                }
                default -> error(exchange, 400, "InvalidRequest");
            }
        }
    }

    private static void error(HttpExchange exchange, int status, String code) throws IOException {
        exchange.getResponseHeaders().set("x-oss-error-code", code);
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            xml(exchange, status, "<Error><Code>" + code + "</Code><Message>synthetic private detail</Message>"
                    + "<RequestId>synthetic-request</RequestId><HostId>private.test</HostId></Error>");
        }
    }

    private static void xml(HttpExchange exchange, int status, String xml) throws IOException {
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void fails(ErrorCode code, org.junit.jupiter.api.function.Executable work) {
        var ex = assertThrows(StorageFailure.class, work);
        assertEquals(code, ex.code());
        assertEquals(code.name(), ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    void unvalidatedAndVersionedBucketsFailClosed() {
        LocationId id = new LocationId(UUID.randomUUID());
        for (String status : java.util.List.of("Enabled", "Suspended")) {
            String bucket = "versioned-" + status.toLowerCase();
            VERSIONING.put(bucket, status);
            try (var provider = new OssStorageProvider(client(USER), Map.of(id, new Location(id, BACKEND, bucket, "")),
                    spool, 1024, 1, Duration.ofSeconds(10), 8, Duration.ofMinutes(1), 2)) {
                fails(ErrorCode.UNSUPPORTED, () -> provider.head(new Locator(id, "object", null), call()));
                fails(ErrorCode.UNSUPPORTED, () -> provider.validateUnversioned(call()));
                fails(ErrorCode.UNSUPPORTED, () -> provider.delete(new Locator(id, "object", null), call()));
            }
        }
    }

    @Test
    void nativePermissionAndCallerDeadlineAreNeutral() {
        try (var denied = new OssStorageProvider(client("wrong"), LOCATIONS, spool, 1024, 1,
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
        try (var scoped = new OssStorageProvider(client(USER), Map.of(id, new Location(id, BACKEND, "contract-a", "area")),
                spool, 1024, 1, Duration.ofSeconds(10), 8, Duration.ofMinutes(1), 2)) {
            scoped.validateUnversioned(call());
            fails(ErrorCode.INVALID, () -> scoped.head(new Locator(id, "other/key", null), call()));
            fails(ErrorCode.INVALID, () -> scoped.list(id, "", null, 1, call()));
        }
        OSS sdk = client(USER);
        try {
            fails(ErrorCode.INVALID, () -> new OssStorageProvider(sdk, Map.of(
                    A, new Location(A, BACKEND, "contract-a", "area"),
                    B, new Location(B, BACKEND, "contract-a", "area/nested")), spool,
                    1024, 1, Duration.ofSeconds(10), 8, Duration.ofMinutes(1), 2));
        } finally { sdk.shutdown(); }
    }
}
