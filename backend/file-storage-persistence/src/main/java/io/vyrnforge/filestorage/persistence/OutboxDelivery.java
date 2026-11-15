package io.vyrnforge.filestorage.persistence;

import io.vyrnforge.platform.kernel.persistence.jdbc.EventTransport;
import io.vyrnforge.platform.kernel.persistence.jdbc.JacksonRecordCodec;
import io.vyrnforge.platform.kernel.persistence.jdbc.JdbcOutboxPublisher;
import io.vyrnforge.platform.kernel.persistence.jdbc.OutboxPublishResult;
import io.vyrnforge.platform.kernel.ids.UuidV7;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import javax.sql.DataSource;

/** One bounded dispatch tick. No scheduler, broker selection or consumer endpoint here. */
public final class OutboxDelivery {
    private final JdbcOutboxPublisher publisher;
    private final DataSource dataSource;
    private final int maxBatch;

    public OutboxDelivery(DataSource dataSource, EventTransport transport, Clock clock,
                          int maxAttempts, Duration retryDelay, Duration lease, int maxBatch) {
        Objects.requireNonNull(transport);
        if (maxBatch < 1) {
            throw new IllegalArgumentException("positive batch bound required");
        }
        this.maxBatch = maxBatch;
        this.dataSource = new KernelDataSource(dataSource, Connection.TRANSACTION_READ_COMMITTED);
        publisher = new JdbcOutboxPublisher(
                this.dataSource,
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

    /** Minimal internal reconciliation projection; no payload or raw transport details. */
    public record DeadEvent(UUID eventId, String type, int attempts, String errorCode) {}

    /** Null scope is explicitly service-wide events only, never all tenants. */
    public List<DeadEvent> dead(UUID tenant, int limit) {
        bound(limit);
        if (tenant != null && !UuidV7.isCanonical(tenant.toString())) {
            throw new IllegalArgumentException("invalid tenant");
        }
        try (var c = dataSource.getConnection(); var ps = c.prepareStatement("""
                SELECT event_id, event_type, attempts, last_error_code
                FROM platform_event_outbox WHERE status='DEAD' AND tenant_id IS NOT DISTINCT FROM ?::uuid
                ORDER BY occurred_at, event_id LIMIT ?
                """)) {
            ps.setObject(1, tenant);
            ps.setInt(2, limit);
            try (var rs = ps.executeQuery()) {
                List<DeadEvent> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new DeadEvent(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getString(4)));
                }
                return List.copyOf(result);
            }
        } catch (SQLException | RuntimeException ex) {
            throw new JdbcTransactions.PersistenceFailure();
        }
    }

    private void bound(int limit) {
        if (limit < 1 || limit > maxBatch) {
            throw new IllegalArgumentException("invalid batch size");
        }
    }

    public OutboxPublishResult tick(int limit) {
        bound(limit);
        try {
            return publisher.publishBatch(limit);
        } catch (RuntimeException ex) {
            throw new JdbcTransactions.PersistenceFailure();
        }
    }
}
