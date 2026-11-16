package io.vyrnforge.filestorage.persistence;

import io.vyrnforge.platform.kernel.persistence.jdbc.*;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;

/** Kernel publisher bound to the service-owned schema; transport is supplied by trusted composition. */
public final class JdbcEventDispatcher {
    private JdbcEventDispatcher(){}
    public static JdbcOutboxPublisher create(DataSource source,EventTransport transport,Clock clock,
            int attempts,Duration retry,Duration lease){
        return new JdbcOutboxPublisher(new KernelDataSource(source,Connection.TRANSACTION_READ_COMMITTED),
            new JacksonRecordCodec(),transport,clock,attempts,retry,lease);
    }
}
