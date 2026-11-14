# Backend — FS-100A
Java 21 / Maven 3.9+; parent + pure domain and provider SPI. Run make ci from root (downloads public Kernel 0.3.0 JAR, verifies published SHA-256, installs locally, then Maven clean verify). No cloud or package token required. Maven Central plugins/JUnit use the same tested pins as Kernel where applicable. Snapshot 0.0.1 is build-only, not approved release numbering.

Domain owns immutable identities, lifecycle transitions and deterministic placement. SPI depends on domain and declares neutral storage operations. No Spring Boot, database, IAM implementation, adapters or live storage. Synthetic tests only. Caller-provided verification/authorization evidence is not a production security control.

## FS-100B
Application module composes domain values through transaction, authorization and configuration ports. Test-only in-memory implementation; no Spring, real provider or database. See product rules and acceptance documents.

## FS-110A
Security module composes trusted credential verification and server-side binding ports with Kernel context pipeline and exact grants. No live verifier or HTTP endpoint. See security contract. Four Maven modules; all use existing Java 21 and Kernel 0.3.0 pins.
