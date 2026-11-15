# Backend — FS-100A
Java 21 / Maven 3.9+; parent + pure domain and provider SPI. Run make ci from root (resolves Kernel 0.3.2 from Maven Central and runs Maven clean verify with strict checksums). No cloud or package token required. Maven Central plugins/JUnit use the same tested pins as Kernel where applicable. Snapshot 0.0.1 is build-only, not approved release numbering.

Domain owns immutable identities, lifecycle transitions and deterministic placement. SPI depends on domain and declares neutral storage operations. No Spring Boot, database, IAM implementation, adapters or live storage. Synthetic tests only. Caller-provided verification/authorization evidence is not a production security control.

## FS-100B
Application module composes domain values through transaction, authorization and configuration ports. Test-only in-memory implementation; no Spring, real provider or database. See product rules and acceptance documents.

## FS-110A
Security module composes trusted credential verification and server-side binding ports with Kernel context pipeline and exact grants. No live verifier or HTTP endpoint. See security contract. Four Maven modules; all use existing Java 21 and Kernel 0.3.2 pins.

## FS-110B
Security module uses pinned Nimbus JOSE+JWT 10.10 for RSA signature verification and JWT/JWK parsing. Explicit key snapshot source must be supplied by composition root. No live discovery or network retrieval, credentials, runtime HTTP or IAM registration. No dependency changes to domain/application/provider SPI.

## FS-120A
Persistence module adds service schema migrations, JDBC transaction adapter and PostgreSQL integration tests. `make ci` now requires Docker for a disposable Testcontainers PostgreSQL instance (no production connection). Flyway schema fs and migration location db/filestorage separate from Kernel migration history. Kernel JDBC integration deferred to FS-120B/C.
