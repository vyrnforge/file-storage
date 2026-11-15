package io.vyrnforge.filestorage.runtime;

import io.vyrnforge.filestorage.persistence.StorageMigrations;
import java.util.Objects;
import javax.sql.DataSource;

/** Read-only isolated dependency readiness, not a production availability or authorization guarantee. */
public final class RuntimeReadiness {
    @FunctionalInterface
    public interface AuthorityProbe {
        /** Trusted composition checks its dependencies; no caller-provided flags. */
        boolean ready();
    }

    private final DataSource source;
    private final AuthorityProbe authority;

    public RuntimeReadiness(DataSource source, AuthorityProbe authority) {
        this.source = Objects.requireNonNull(source);
        this.authority = Objects.requireNonNull(authority);
    }

    public boolean ready() {
        try {
            return authority.ready() && StorageMigrations.ready(source);
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
