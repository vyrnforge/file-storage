package io.vyrnforge.filestorage.persistence;

import io.vyrnforge.platform.kernel.persistence.jdbc.EventTransport;
import io.vyrnforge.platform.kernel.persistence.jdbc.JacksonRecordCodec;
import io.vyrnforge.platform.kernel.persistence.jdbc.JdbcOutboxPublisher;
import io.vyrnforge.platform.kernel.persistence.jdbc.OutboxPublishResult;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import javax.sql.DataSource;

/** One bounded dispatch tick. No scheduler, broker selection or consumer endpoint here. */
public final class OutboxDelivery {
    private final JdbcOutboxPublisher publisher;
    private final int maxBatch;

    public OutboxDelivery(DataSource dataSource, EventTransport transport, Clock clock,
                          int maxAttempts, Duration retryDelay, Duration lease, int maxBatch) {
        Objects.requireNonNull(transport);
        if (maxBatch < 1) {
            throw new IllegalArgumentException("positive batch bound required");
        }
        this.maxBatch = maxBatch;
        publisher = new JdbcOutboxPublisher(
                new KernelDataSource(dataSource, Connection.TRANSACTION_READ_COMMITTED),
                new JacksonRecordCodec(), event -> {
                    try {
                        transport.publish(event);
                    } catch (Exception ex) {
                        // Kernel persists the exception class as a code. Never retain
                        // transport-specific class names/messages that may contain private details.
                        throw new DeliveryFailed();
                    }
                }, clock, maxAttempts, retryDelay, lease);
    }

    private static final class DeliveryFailed extends Exception {
    }

    public OutboxPublishResult tick(int limit) {
        if (limit < 1 || limit > maxBatch) {
            throw new IllegalArgumentException("invalid batch size");
        }
        try {
            return publisher.publishBatch(limit);
        } catch (RuntimeException ex) {
            throw new JdbcTransactions.PersistenceFailure();
        }
    }
}
