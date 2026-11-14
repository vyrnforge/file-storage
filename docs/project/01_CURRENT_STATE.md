# Current state and legacy capability baseline

Date: 2026-11-14. Phase 1 FS-100A implemented on review branch. Java/Maven domain and provider SPI only; no service runtime, database, deployment or released product. Legacy evidence: user-provided private src.zip (77 files; 13 Java test files), not a full Git checkout. Archive inspected statically; no build or tests executed. Do not publish the archive or private configuration.

## Legacy inventory (sanitized)
| Capability | Legacy evidence | Proposed treatment |
|---|---|---|
| Upload policy / completion | FileController, FileService, OssService | Preserve direct-upload behavior, constrain signing and verify completion |
| Batch/status/progress/cancel/refresh | FileController, UploadSession | Preserve semantics; pin session placement |
| Download URL, list and pagination | FileController, FileService | Preserve APIs where compatibility requires; strengthen authorization |
| Tenant usage and reservations | TenantQuotaService, StorageUsageService | Preserve accounting; concurrency and cleanup regression tests |
| Metadata and object key | FileMetadata, UploadSession | Evolve logical ID/version/object locator |
| Alibaba OSS | OssConfig, OssProperties, OssService | Extract adapter; remove domain SDK coupling |
| Cleanup/reconciliation | CleanupWorkerService, StorageReconciliationService | Preserve outcomes; bound inventory and recover failures |
| Identity/audit/health | security, monitoring packages | Integrate kernel and durable audit; verify trust boundary |
| Schema/tests | Flyway V1–V4, Java tests | Use as behavior reference; do not blindly import migrations |

Legacy source exposes /v1/files operations for listing, usage, reconciliation, download URL, upload initiation/batch/status/cancel/refresh/progress/completion and deletion. The old model associates files and sessions with object keys and a startup-configured OSS backend. Multi-provider placement requires a durable location association.

## Audit follow-up
Private security review, credential hygiene, full legacy repository/build manifest and version inventory, regression execution, production data/traffic and backup evidence remain open. Legacy source is private input; retain it separately. Do not assume active production data or a working test suite.

## New repo status
Specified: approved architecture and FS-100A acceptance gates. Implemented on review branch: domain/SPI and Maven CI; on main: documentation and repository CI/hygiene controls. Applied: active default-branch ruleset and squash-only repository merge settings. Verified: PR/main CI passed, negative CI PR blocked. No service runtime or production security claims. See [repository security evidence](../security/REPOSITORY_SECURITY_EVIDENCE.md). Phase 0 owner approval recorded; FS-100A only authorized. See [implementation evidence](08_FS100A_EVIDENCE.md).

## Owner approval, 2026-11-14
DEC-FS001–010, roadmap, Java 21/Maven/Spring Boot/PostgreSQL/Flyway, public visibility and Apache-2.0 approved. Remaining available GitHub security settings owner-confirmed, not independently read back. FS-100A authorized; no later runtime work. Full legacy build, credential hygiene and production targets remain open. See [FS-100A implementation evidence](08_FS100A_EVIDENCE.md) for actual test results.
