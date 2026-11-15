package io.vyrnforge.filestorage.persistence;

import io.vyrnforge.filestorage.application.FileApplication.Code;
import io.vyrnforge.filestorage.application.FileApplication.Failure;
import io.vyrnforge.filestorage.domain.StorageModel.BackendId;
import java.sql.Connection;
import java.sql.SQLException;

/** Caller holds the backend identity lock; all revision writers share that lock. */
final class BackendRevisionGuard {
    private BackendRevisionGuard() {}

    static void rejectSuperseded(Connection connection, BackendId backend, long candidate) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT 1 FROM fs.backend_revision
                WHERE backend_id=? AND config_revision>? LIMIT 1
                """)) {
            statement.setObject(1, backend.value());
            statement.setLong(2, candidate);
            try (var rows = statement.executeQuery()) {
                if (rows.next()) throw new Failure(Code.CONFLICT);
            }
        }
    }
}
