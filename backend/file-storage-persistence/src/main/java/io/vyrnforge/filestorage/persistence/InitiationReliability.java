package io.vyrnforge.filestorage.persistence;

import io.vyrnforge.filestorage.application.FileApplication.*;
import io.vyrnforge.platform.kernel.idempotency.IdempotencyRequest;
import io.vyrnforge.platform.kernel.context.CorrelationContext;
import io.vyrnforge.platform.kernel.idempotency.IdempotencyDecision;
import io.vyrnforge.platform.kernel.persistence.jdbc.IdempotencyResultCodec;
import io.vyrnforge.platform.kernel.persistence.jdbc.JacksonRecordCodec;
import io.vyrnforge.platform.kernel.persistence.jdbc.JdbcIdempotencyPort;
import io.vyrnforge.platform.kernel.persistence.jdbc.JdbcReliabilityTransaction;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.sql.DataSource;

/** Coordinates Kernel leases with the service-owned aggregate on the same database. */
final class InitiationReliability {
    private static final String OPERATION = "file-storage.upload.initiate";
    private final JdbcIdempotencyPort<String> idempotency;
    private final JdbcReliabilityTransaction transaction;
    private final Clock clock;
    private final Duration leaseTtl;

    InitiationReliability(DataSource dataSource, Clock clock, Duration leaseTtl) {
        this.clock = Objects.requireNonNull(clock);
        this.leaseTtl = Objects.requireNonNull(leaseTtl);
        if (leaseTtl.isZero() || leaseTtl.isNegative()) {
            throw new IllegalArgumentException("positive lease TTL required");
        }
        idempotency = new JdbcIdempotencyPort<>(
                new KernelDataSource(dataSource, Connection.TRANSACTION_READ_COMMITTED),
                IdempotencyResultCodec.strings(), clock);
        transaction = new JdbcReliabilityTransaction(
                new KernelDataSource(dataSource, Connection.TRANSACTION_REPEATABLE_READ),
                new JacksonRecordCodec(), clock);
    }

    View initiate(RequestIdentity identity, Initiate command, CorrelationContext correlation, Function<Unit, View> work,
                  Function<Unit, View> replay, Transactions reads) {
        Caller caller = identity.caller();
        if (!identity.key().equals(command.requestKey())) {
            throw new Failure(Code.INVALID);
        }
        // Preserve actor/application-bound request identity within the Kernel tenant scope.
        String key = digest("key-v1", caller.actor(), caller.consumer(), identity.key());
        String fingerprint = digest("request-v1", caller.tenant().value().toString(),
                caller.actor(), caller.consumer(), command.purpose(), command.name(),
                Long.toString(command.expectedSize()), command.mimeType(), command.requestKey());
        var request = new IdempotencyRequest(caller.tenant().value(), OPERATION, key, fingerprint,
                clock.instant().plus(leaseTtl));
        var decision = begin(request);
        return switch (decision.type()) {
            case CONFLICT -> throw new Failure(Code.CONFLICT);
            case IN_PROGRESS -> throw new Failure(Code.IN_PROGRESS);
            case REPLAY -> {
                // Re-read the aggregate and reauthorize, never replay a cached access decision.
                View view = reads.execute(replay);
                if (!view.session().value().toString().equals(decision.replay().result())) {
                    throw new JdbcTransactions.PersistenceFailure();
                }
                yield view;
            }
            case ACQUIRED -> {
                var lease = decision.lease();
                var result = new AtomicReference<View>();
                try {
                    transaction.executeIdempotent(lease, IdempotencyResultCodec.strings(),
                            (connection, events, audits) -> {
                                View view = JdbcTransactions.onConnection(connection, work,
                                        entry -> MutationIntents.initiated(events, audits, clock, correlation, entry));
                                result.set(view);
                                return view.session().value().toString();
                            });
                    yield Objects.requireNonNull(result.get());
                } catch (RuntimeException ex) {
                    // Only abort our active lease; completed/replaced leases cannot be deleted.
                    try {
                        idempotency.abort(lease);
                    } catch (RuntimeException ignored) {
                        // Expiry/retry provides recovery; never expose raw SQL details.
                    }
                    if (ex instanceof Failure || ex instanceof JdbcTransactions.PersistenceFailure) {
                        throw ex;
                    }
                    throw new JdbcTransactions.PersistenceFailure();
                }
            }
        };
    }

    private IdempotencyDecision<String> begin(IdempotencyRequest request) {
        try {
            return idempotency.begin(request);
        } catch (RuntimeException ex) {
            throw new JdbcTransactions.PersistenceFailure();
        }
    }

    private static String digest(String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
