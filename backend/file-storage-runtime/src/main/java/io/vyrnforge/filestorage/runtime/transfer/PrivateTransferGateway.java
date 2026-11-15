package io.vyrnforge.filestorage.runtime.transfer;

import io.vyrnforge.filestorage.spi.StorageProvider;
import io.vyrnforge.filestorage.spi.StorageProvider.StorageFailure;
import io.vyrnforge.filestorage.runtime.transfer.TransferFailure.Code;
import io.vyrnforge.filestorage.runtime.transfer.TransferInstruction.Action;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.function.LongConsumer;

/** Provider-neutral bounded data plane. No metadata writes or transaction across byte I/O. */
public final class PrivateTransferGateway {
    private final TransferAuthority authority;
    private final TransferProviderResolver resolver;
    private final TransferLimits limits;
    private final Clock clock;
    private final Semaphore slots;

    public PrivateTransferGateway(TransferAuthority authority, TransferProviderResolver resolver,
            TransferLimits limits, Clock clock) {
        this.authority = Objects.requireNonNull(authority);
        this.resolver = Objects.requireNonNull(resolver);
        this.limits = Objects.requireNonNull(limits);
        this.clock = Objects.requireNonNull(clock);
        this.slots = new Semaphore(limits.concurrency());
    }

    /** Caller owns input. Provider contract enforces exact length and create-only write. */
    public void upload(String bearer, String handle, long declaredSize, InputStream input) {
        Objects.requireNonNull(input);
        if (declaredSize < -1) throw new TransferFailure(Code.INVALID);
        if (declaredSize > limits.maxBytes()) throw new TransferFailure(Code.TOO_LARGE);
        execute(bearer, handle, Action.UPLOAD, (instruction, provider, deadline) -> {
            if (declaredSize >= 0 && declaredSize != instruction.size()) throw new TransferFailure(Code.INVALID);
            var metadata = provider.put(instruction.locator(), new DeadlineInput(input, deadline),
                    instruction.size(), deadline.call());
            deadline.check();
            if (metadata == null || metadata.size() != instruction.size()) throw new TransferFailure(Code.UNAVAILABLE);
        });
    }

    /** Caller owns output. Close provider stream/lease on success, interruption and client failure. */
    public void download(String bearer, String handle, OutputStream output) {
        download(bearer, handle, ignored -> {}, output);
    }

    /** Announce authoritative length only after authorization and provider stream acquisition. */
    public void download(String bearer, String handle, LongConsumer length, OutputStream output) {
        Objects.requireNonNull(output);
        Objects.requireNonNull(length);
        execute(bearer, handle, Action.DOWNLOAD, (instruction, provider, deadline) -> {
            try (var input = new DeadlineInput(provider.get(instruction.locator(), deadline.call()), deadline)) {
                // Do not advertise a zero-byte success before checking the stream.
                if (instruction.size() > 0) length.accept(instruction.size());
                byte[] buffer = new byte[limits.bufferBytes()];
                long remaining = instruction.size();
                while (remaining > 0) {
                    deadline.check();
                    int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (count <= 0) throw new TransferFailure(Code.UNAVAILABLE);
                    // Check for extra bytes before releasing the final chunk.
                    if (count == remaining && input.read() != -1) throw new TransferFailure(Code.UNAVAILABLE);
                    output.write(buffer, 0, count);
                    remaining -= count;
                }
                if (instruction.size() == 0) {
                    if (input.read() != -1) throw new TransferFailure(Code.UNAVAILABLE);
                    length.accept(0);
                }
                deadline.check();
            }
        });
    }

    private void execute(String bearer, String handle, Action action, Work work) {
        if (bearer == null || bearer.isBlank() || bearer.length() > 16384
                || bearer.chars().anyMatch(Character::isWhitespace)
                || handle == null || !handle.matches("[A-Za-z0-9_-]{32,128}"))
            throw new TransferFailure(Code.DENIED);
        if (!slots.tryAcquire()) throw new TransferFailure(Code.BUSY);
        try {
            var deadline = new Deadline(limits.timeout());
            TransferInstruction instruction;
            try {
                instruction = authority.claim(bearer, handle, action);
            } catch (RuntimeException ex) {
                throw new TransferFailure(Code.DENIED);
            }
            if (instruction == null || instruction.action() != action || !clock.instant().isBefore(instruction.expiresAt()))
                throw new TransferFailure(Code.DENIED);
            if (instruction.size() > limits.maxBytes()) throw new TransferFailure(Code.TOO_LARGE);
            deadline.check();
            try (var lease = Objects.requireNonNull(resolver.resolve(instruction))) {
                deadline.check();
                work.run(instruction, Objects.requireNonNull(lease.provider()), deadline);
            }
        } catch (TransferFailure ex) {
            throw ex;
        } catch (StorageFailure ex) {
            throw new TransferFailure(switch (ex.code()) {
                case INVALID -> Code.INVALID;
                case NOT_FOUND -> Code.NOT_FOUND;
                case CONFLICT -> Code.CONFLICT;
                case FORBIDDEN -> Code.DENIED;
                case TIMEOUT -> Code.TIMEOUT;
                case TRANSIENT, UNSUPPORTED -> Code.UNAVAILABLE;
            });
        } catch (IOException | RuntimeException ex) {
            throw new TransferFailure(Code.UNAVAILABLE);
        } finally {
            slots.release();
        }
    }

    @FunctionalInterface
    private interface Work {
        void run(TransferInstruction instruction, StorageProvider provider, Deadline deadline) throws IOException;
    }

    /** Monotonic cooperative deadline; blocking transport deadlines belong to adapter/container. */
    private static final class Deadline {
        private final long start = System.nanoTime();
        private final long nanos;
        Deadline(Duration timeout) { nanos = timeout.toNanos(); }
        void check() {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() - start >= nanos)
                throw new TransferFailure(Code.TIMEOUT);
        }
        StorageProvider.Call call() {
            check();
            return new StorageProvider.Call(Duration.ofNanos(Math.max(1, nanos - (System.nanoTime() - start))));
        }
    }

    private static final class DeadlineInput extends FilterInputStream {
        private final Deadline deadline;
        DeadlineInput(InputStream input, Deadline deadline) {
            super(Objects.requireNonNull(input));
            this.deadline = deadline;
        }
        @Override public int read() throws IOException {
            deadline.check();
            int result = in.read();
            deadline.check();
            return result;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            deadline.check();
            int result = in.read(bytes, offset, length);
            deadline.check();
            return result;
        }
    }
}
