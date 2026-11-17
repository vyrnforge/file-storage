package io.vyrnforge.filestorage.runtime;

import io.vyrnforge.filestorage.domain.StorageModel.BackendId;
import io.vyrnforge.filestorage.domain.StorageModel.FileId;
import io.vyrnforge.filestorage.domain.StorageModel.LocationId;
import io.vyrnforge.filestorage.domain.StorageModel.Locator;
import io.vyrnforge.filestorage.domain.StorageModel.SessionId;
import io.vyrnforge.filestorage.domain.StorageModel.TenantId;
import io.vyrnforge.filestorage.domain.StorageModel.VersionId;
import io.vyrnforge.filestorage.runtime.transfer.PrivateTransferGateway;
import io.vyrnforge.filestorage.runtime.transfer.TransferAuthority;
import io.vyrnforge.filestorage.runtime.transfer.TransferFailure;
import io.vyrnforge.filestorage.runtime.transfer.TransferInstruction;
import io.vyrnforge.filestorage.runtime.transfer.TransferLimits;
import io.vyrnforge.filestorage.runtime.transfer.TransferProviderResolver;
import io.vyrnforge.filestorage.spi.StorageProvider;
import io.vyrnforge.filestorage.spi.StorageProvider.StorageFailure;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QC-01 characterization only: the current deadline is cooperative.
 * These tests intentionally expose the existing stalled-client limitation.
 * Replace the 'still blocked' assertions with positive deadline guarantees
 * in a separately approved QC-02 transport hardening change.
 */
class TransferDeadlineCharacterizationTest {
    private static final String PROOF = "synthetic-proof";
    private static final byte[] ONE_BYTE = {(byte) 42};
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration DEADLINE = Duration.ofMillis(100);
    private static final Duration BLOCKED_OBSERVATION = Duration.ofMillis(300);
    private static final TenantId TENANT = new TenantId(
            UUID.fromString("01900000-0000-7000-8000-000000000001"));
    private static final BackendId BACKEND = new BackendId(
            UUID.fromString("01900000-0000-7000-8000-000000000002"));
    private static final Locator OBJECT = new Locator(
            new LocationId(UUID.fromString("01900000-0000-7000-8000-000000000003")),
            "private/characterization", null);

    @Test
    void blockedUploadInputOutlivesCooperativeDeadlineUntilReleased() throws Exception {
        Fixture fixture = new Fixture();
        PrivateTransferGateway gateway = fixture.gateway();
        GateInputStream stalledInput = new GateInputStream();
        String first = fixture.issue(TransferInstruction.Action.UPLOAD);
        String second = fixture.issue(TransferInstruction.Action.UPLOAD);

        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> inFlight = workers.submit(
                    () -> gateway.upload(PROOF, first, ONE_BYTE.length, stalledInput));
            try {
                assertTrue(stalledInput.entered.await(5, TimeUnit.SECONDS));
                // A cooperative check cannot interrupt the already blocked input.read().
                assertThrows(TimeoutException.class,
                        () -> inFlight.get(BLOCKED_OBSERVATION.toMillis(), TimeUnit.MILLISECONDS));
                assertEquals(TransferFailure.Code.BUSY, assertThrows(TransferFailure.class,
                        () -> gateway.upload(PROOF, second, ONE_BYTE.length,
                                new ByteArrayInputStream(ONE_BYTE))).code());
                assertTrue(fixture.handles.containsKey(second), "busy must not consume the handle");
            } finally {
                stalledInput.release.countDown();
            }
            assertFailure(TransferFailure.Code.TIMEOUT, inFlight);
        }

        assertEquals(1, fixture.leaseCloses.get(), "timeout must release the provider lease");
        gateway.upload(PROOF, second, ONE_BYTE.length, new ByteArrayInputStream(ONE_BYTE));
        assertEquals(2, fixture.leaseCloses.get(), "a released slot admits a later transfer");
    }

    @Test
    void blockedDownloadOutputOutlivesCooperativeDeadlineUntilReleased() throws Exception {
        Fixture fixture = new Fixture();
        PrivateTransferGateway gateway = fixture.gateway();
        GateOutputStream stalledOutput = new GateOutputStream();
        String first = fixture.issue(TransferInstruction.Action.DOWNLOAD);
        String second = fixture.issue(TransferInstruction.Action.DOWNLOAD);

        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> inFlight = workers.submit(() -> gateway.download(PROOF, first, stalledOutput));
            try {
                assertTrue(stalledOutput.entered.await(5, TimeUnit.SECONDS));
                // A completed provider read does not interrupt a stalled output.write().
                assertThrows(TimeoutException.class,
                        () -> inFlight.get(BLOCKED_OBSERVATION.toMillis(), TimeUnit.MILLISECONDS));
                assertEquals(TransferFailure.Code.BUSY, assertThrows(TransferFailure.class,
                        () -> gateway.download(PROOF, second, new ByteArrayOutputStream())).code());
                assertTrue(fixture.handles.containsKey(second), "busy must not consume the handle");
            } finally {
                stalledOutput.release.countDown();
            }
            assertFailure(TransferFailure.Code.TIMEOUT, inFlight);
        }

        assertEquals(1, fixture.leaseCloses.get(), "timeout must release the provider lease");
        assertEquals(1, fixture.streamCloses.get(), "timeout must close the provider stream");
        ByteArrayOutputStream recovered = new ByteArrayOutputStream();
        gateway.download(PROOF, second, recovered);
        assertArrayEquals(ONE_BYTE, recovered.toByteArray());
        assertEquals(2, fixture.leaseCloses.get());
        assertEquals(2, fixture.streamCloses.get());
    }

    private static void assertFailure(TransferFailure.Code code, Future<?> inFlight) {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> inFlight.get(5, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof TransferFailure);
        assertEquals(code, ((TransferFailure) failure.getCause()).code());
    }

    private static final class GateInputStream extends InputStream {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean started = new AtomicBoolean();

        @Override
        public int read() throws IOException {
            if (!started.compareAndSet(false, true)) {
                return -1;
            }
            entered.countDown();
            awaitRelease(release);
            return ONE_BYTE[0] & 0xff;
        }
    }

    private static final class GateOutputStream extends OutputStream {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void write(int value) throws IOException {
            entered.countDown();
            awaitRelease(release);
        }
    }

    private static void awaitRelease(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IOException("synthetic stalled-client fixture timed out");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("synthetic stalled-client fixture interrupted", failure);
        }
    }

    private static final class Fixture implements TransferAuthority, TransferProviderResolver {
        final ConcurrentHashMap<String, TransferInstruction> handles = new ConcurrentHashMap<>();
        final AtomicInteger leaseCloses = new AtomicInteger();
        final AtomicInteger streamCloses = new AtomicInteger();

        final StorageProvider provider = new StorageProvider() {
            @Override
            public Set<Capability> capabilities() {
                return Set.of();
            }

            @Override
            public Metadata put(Locator object, InputStream input, long expectedSize, Call call) {
                assertEquals(OBJECT, object);
                try {
                    int value = input.read();
                    if (expectedSize != 1 || value != (ONE_BYTE[0] & 0xff) || input.read() != -1) {
                        throw new StorageFailure(ErrorCode.INVALID);
                    }
                    return new Metadata(1, null, "application/octet-stream");
                } catch (IOException failure) {
                    throw new StorageFailure(ErrorCode.TRANSIENT);
                }
            }

            @Override
            public InputStream get(Locator object, Call call) {
                assertEquals(OBJECT, object);
                return new ByteArrayInputStream(ONE_BYTE) {
                    @Override
                    public void close() {
                        streamCloses.incrementAndGet();
                    }
                };
            }

            @Override
            public Metadata head(Locator object, Call call) {
                throw new AssertionError("unexpected HEAD");
            }

            @Override
            public void delete(Locator object, Call call) {
                throw new AssertionError("unexpected delete");
            }

            @Override
            public Page list(LocationId location, String prefix, String cursor, int limit, Call call) {
                throw new AssertionError("unexpected list");
            }
        };

        String issue(TransferInstruction.Action action) {
            String handle = UUID.randomUUID().toString().replace("-", "")
                    + UUID.randomUUID().toString().replace("-", "");
            TransferInstruction instruction = new TransferInstruction(
                    TENANT, "fixture-actor", "asset", action,
                    new FileId("characterization-file"), new VersionId(UUID.randomUUID()),
                    new SessionId(UUID.randomUUID()), BACKEND, 1, OBJECT, 1,
                    NOW.plusSeconds(60));
            handles.put(handle, instruction);
            return handle;
        }

        PrivateTransferGateway gateway() {
            return new PrivateTransferGateway(this, this,
                    new TransferLimits(1024, 1, 1024, DEADLINE), CLOCK);
        }

        @Override
        public TransferInstruction claim(String proof, String handle, TransferInstruction.Action action) {
            if (!PROOF.equals(proof)) {
                throw new IllegalArgumentException("synthetic unauthorized proof");
            }
            TransferInstruction instruction = handles.get(handle);
            if (instruction == null || instruction.action() != action
                    || !handles.remove(handle, instruction)) {
                throw new IllegalArgumentException("synthetic invalid handle");
            }
            return instruction;
        }

        @Override
        public Lease resolve(TransferInstruction instruction) {
            assertEquals(BACKEND, instruction.backend());
            assertEquals(OBJECT, instruction.locator());
            return new Lease() {
                @Override
                public StorageProvider provider() {
                    return Fixture.this.provider;
                }

                @Override
                public void close() {
                    leaseCloses.incrementAndGet();
                }
            };
        }
    }
}
