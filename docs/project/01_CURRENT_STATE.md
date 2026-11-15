# Current state and legacy capability baseline

Date: 2026-11-14. Phase 1 FS-100A merged; FS-100B application foundation merged. Java/Maven domain, provider SPI and application; no service runtime, database, deployment or released product. Legacy evidence: user-provided private src.zip (77 files; 13 Java test files), not a full Git checkout. Archive inspected statically; no build or tests executed. Do not publish the archive or private configuration.

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
Specified: approved architecture and FS-100A acceptance gates. Implemented on review branch: domain/SPI and Maven CI; on main: documentation and repository CI/hygiene controls. Applied: active default-branch ruleset and squash-only repository merge settings. Verified: PR/main CI passed, negative CI PR blocked. No service runtime or production security claims. See [repository security evidence](../security/REPOSITORY_SECURITY_EVIDENCE.md). Phase 0 owner approval recorded; FS-100B authorized as next bounded increment. See [implementation evidence](08_FS100A_EVIDENCE.md).

## Owner approval, 2026-11-14
DEC-FS001–010, roadmap, Java 21/Maven/Spring Boot/PostgreSQL/Flyway, public visibility and Apache-2.0 approved. Remaining available GitHub security settings owner-confirmed, not independently read back. FS-100A merged; owner directed continuation with product/business analysis; FS-100B only in scope. Full legacy build, credential hygiene and production targets remain open. See [FS-100A implementation evidence](08_FS100A_EVIDENCE.md) for actual test results.

FS-100A hosted verification: run 43609642326, ten tests passed, zero failures/errors/skips, dependency boundaries and Kernel checksum verified. Local verification remains open; PR #5 merged at 2effe678943242260c64ef252bae7378aa61434a; final head CI 43609728550 succeeded.

FS-100B: product outcome, decision rules, acceptance scenarios, application use cases and transaction/authorization ports merged. No live bytes, grants, database, IAM or public API. Local full build remains unavailable (Maven absent, GitHub DNS failed); no waiver implied. See [FS-100B acceptance](10_FS100B_ACCEPTANCE.md).


2026-11-15: Kernel 0.3.2 dependency maintenance approved by owner. Public Central resolution verified in File Storage PR #8 CI run 43664121720: 18 tests passed (8 domain, 2 SPI, 8 application), no failures/errors/skips. No service runtime or production readiness claim. FS-110/FS-120 remain future increments.
