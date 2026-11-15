# Backend — FS-100A
Java 21 / Maven 3.9+; parent + pure domain and provider SPI. Run make ci from root (resolves public Kernel 0.3.2 from Maven Central and runs Maven clean verify with strict checksums). No cloud or package token required. Maven Central plugins/JUnit use the same tested pins as Kernel where applicable. Snapshot 0.0.1 is build-only, not approved release numbering.

Domain owns immutable identities, lifecycle transitions and deterministic placement. SPI depends on domain and declares neutral storage operations. No Spring Boot, database, IAM implementation, adapters or live storage. Synthetic tests only. Caller-provided verification/authorization evidence is not a production security control.

## FS-100B
Application module composes domain values through transaction, authorization and configuration ports. Test-only in-memory implementation; no Spring, real provider or database. See product rules and acceptance documents.
