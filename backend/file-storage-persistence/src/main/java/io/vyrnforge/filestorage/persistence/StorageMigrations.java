package io.vyrnforge.filestorage.persistence;

import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/** Explicit schema migration and non-mutating migration readiness checks. */
public final class StorageMigrations {
    private StorageMigrations() {}

    public static void migrate(DataSource dataSource) {
        storage(dataSource).migrate();
        kernel(dataSource).migrate();
    }

    /** Never migrate or repair at runtime. Missing, pending or invalid migrations fail closed. */
    public static boolean ready(DataSource dataSource) {
        try {
            return current(storage(dataSource)) && current(kernel(dataSource));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static boolean current(Flyway flyway) {
        return flyway.validateWithResult().validationSuccessful
                && flyway.info().current() != null && flyway.info().pending().length == 0;
    }

    private static Flyway storage(DataSource source) {
        return configured(source, "fs", "classpath:db/filestorage");
    }

    private static Flyway kernel(DataSource source) {
        return configured(source, "fs_kernel", "classpath:db/migration");
    }

    private static Flyway configured(DataSource source, String schema, String location) {
        return Flyway.configure().dataSource(Objects.requireNonNull(source))
                .schemas(schema).defaultSchema(schema).locations(location)
                .cleanDisabled(true).baselineOnMigrate(false).load();
    }
}
