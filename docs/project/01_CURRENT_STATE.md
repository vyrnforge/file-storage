# Current state and legacy capability baseline

Date: 2026-11-15. Phase 1 FS-100A merged; FS-100B merged; FS-110A merged; FS-110B verifier on review branch. Java/Maven domain, provider SPI, application and security; no service runtime, database, deployment or released product. Legacy evidence: user-provided private src.zip (77 files; 13 Java test files), not a full Git checkout. Archive inspected statically; no build or tests executed. Do not publish the archive or private configuration.

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
Specified: approved architecture and FS-100A acceptance gates. Implemented on main: domain/SPI/application/security and Maven CI; on review branch: FS-110B JWT verification. Applied: active default-branch ruleset and squash-only repository merge settings. Verified: PR/main CI passed, negative CI PR blocked. No service runtime or production security claims. See [repository security evidence](../security/REPOSITORY_SECURITY_EVIDENCE.md). Phase 0 owner approval recorded; FS-110 authorized as next bounded increment. See [implementation evidence](08_FS100A_EVIDENCE.md).

## Owner approval, 2026-11-14
DEC-FS001–010, roadmap, Java 21/Maven/Spring Boot/PostgreSQL/Flyway, public visibility and Apache-2.0 approved. Remaining available GitHub security settings owner-confirmed, not independently read back. FS-100A merged; owner directed continuation with product/business analysis; FS-100B merged; FS-110A merged; FS-110B verifier in scope. Full legacy build, credential hygiene and production targets remain open. See [FS-100A implementation evidence](08_FS100A_EVIDENCE.md) for actual test results.

FS-100A hosted verification: run 43609642326, ten tests passed, zero failures/errors/skips, dependency boundaries and Kernel checksum verified. Local verification remains open; PR #5 merged at 2effe678943242260c64ef252bae7378aa61434a; final head CI 43609728550 succeeded.

FS-100B: product outcome, decision rules, acceptance scenarios, application use cases and transaction/authorization ports merged. No live bytes, grants, database, IAM or public API. Local full build remains unavailable (Maven absent, GitHub DNS failed); no waiver implied. See [FS-100B acceptance](10_FS100B_ACCEPTANCE.md).

FS-100B PR #6 merged at 0f08b3054c6835c8f40913c38ca09c8d359d6ce1; final hosted CI 43610665807 passed. FS-110A implements actual Kernel pipeline, trusted verifier/binding ports and exact file grants; no live JWT verifier, IAM deployment, durable grant store or HTTP. See [identity security contract](../security/FS110_IDENTITY_AUTHORIZATION.md). Local full build remains outstanding; Java 21 present, Maven absent and GitHub DNS unavailable in execution environment.

FS-110A hosted CI 43611269131 passed 25 tests, four-module Maven reactor, checksum and dependency checks. PR #7 merged; local verification and FS-110B live verifier/authority integration pending. Wyrmgate source shows aud=clientId, not a separate storage resource audience; no arbitrary client login token accepted by design.

2026-11-15: PR #7 merged at 69d0ef978a5f04c283331851c00368632639de5c; final CI 43611373559 successful. Owner directed next. FS-110B verifier slice uses Nimbus JOSE+JWT and explicit trusted JWKS snapshots, tests real signed synthetic access tokens. Live IAM registration/key transport/authority store remain unconfigured. See [FS-110B](../security/FS110B_JWT_VERIFIER.md). FS-120 not started.

FS-110B verifier implementation hosted CI 43665624103 passed 31 tests (including 6 real-signature test methods), no failures/errors/skips. PR #9 review pending; no live Wyrmgate registration, retrieval, binding or HTTP runtime. Local toolchain/DNS gap reconfirmed.

2026-11-15: Owner approved Kernel v0.3.2 dependency maintenance. PR #8 initially passed 18 tests against Central on the FS-100B baseline; rebased/merged with current FS-110A to preserve all security work. Fresh CI required on combined revision. No live verifier, DB or deployment claim.
PR #8 merged at 43f1c5552a3202ad0724e516fe51c03886373591. PR #9 conflict resolution preserves Kernel 0.3.2 Central resolution, FileReference length enforcement and JWT verifier. Previous FS-110B CI used Kernel 0.3.0; combined head requires fresh CI.

2026-11-15: Owner requested Maven POM readability maintenance before the next implementation. Reformat all five POMs without changing XML values, element order, dependencies or build plugins; add lightweight formatting guard to make ci and editor guidance. No FS-120 authorization or runtime changes.
