package io.vyrnforge.filestorage.persistence;

import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/** Explicit service schema migration; no production startup or destructive clean. */
public final class StorageMigrations {
    private StorageMigrations() {
    }

    public static void migrate(DataSource dataSource) {
        Flyway.configure()
                .dataSource(Objects.requireNonNull(dataSource))
                .schemas("fs")
                .defaultSchema("fs")
                .locations("classpath:db/filestorage")
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .load()
                .migrate();
    }
}
