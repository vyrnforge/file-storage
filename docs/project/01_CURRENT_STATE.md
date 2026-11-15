# Current state and legacy capability baseline

Date: 2026-11-15. Phase 1 FS-100A merged; FS-100B merged; FS-110A merged; FS-110B verifier merged; FS-120A merged; FS-120B1 merged (PR #12); FS-120B2A merged (PR #52); FS-120B2B merged (PR #53); FS-120C1 merged (PR #54); FS-120C2 in review. Java/Maven domain, provider SPI, application, security and persistence; no deployed service, production database, or released product. Legacy evidence: user-provided private src.zip (77 files; 13 Java test files), not a full Git checkout. Archive inspected statically; no build or tests executed. Do not publish the archive or private configuration.

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
Specified: approved architecture, roadmap and acceptance contracts through FS-120B1. Implemented on main: domain/SPI/application, cryptographic verification and authorization boundary, PostgreSQL persistence and Kernel transactional initiation/idempotency; no live IAM wiring, real provider transfer, HTTP runtime or console. Applied: active default-branch ruleset and squash-only repository merge settings; disposable test database migrations only. Verified: hosted CI through PR #12 passed 46 tests (15 real PostgreSQL), final head CI 43667799363 passed, and negative CI PR blocked. Local full verification remains outstanding. No service runtime or production security claims. See [repository security evidence](../security/REPOSITORY_SECURITY_EVIDENCE.md) and [transactional initiation](../architecture/06_TRANSACTIONAL_INITIATION.md).

## Owner approval, 2026-11-14
DEC-FS001–010, roadmap, Java 21/Maven/Spring Boot/PostgreSQL/Flyway, public visibility and Apache-2.0 approved. Remaining available GitHub security settings owner-confirmed, not independently read back. FS-100A merged; owner directed continuation with product/business analysis; FS-100B merged; FS-110A merged; FS-110B verifier in scope. Full legacy build, credential hygiene and production targets remain open. See [FS-100A implementation evidence](08_FS100A_EVIDENCE.md) for actual test results.

FS-100A hosted verification: run 43609642326, ten tests passed, zero failures/errors/skips, dependency boundaries and Kernel checksum verified. Local verification remains open; PR #5 merged at 2effe678943242260c64ef252bae7378aa61434a; final head CI 43609728550 succeeded.

FS-100B: product outcome, decision rules, acceptance scenarios, application use cases and transaction/authorization ports merged. No live bytes, grants, database, IAM or public API. Local full build remains unavailable (Maven absent, GitHub DNS failed); no waiver implied. See [FS-100B acceptance](10_FS100B_ACCEPTANCE.md).

FS-100B PR #6 merged at 0f08b3054c6835c8f40913c38ca09c8d359d6ce1; final hosted CI 43610665807 passed. FS-110A implements actual Kernel pipeline, trusted verifier/binding ports and exact file grants; no live JWT verifier, IAM deployment, durable grant store or HTTP. See [identity security contract](../security/FS110_IDENTITY_AUTHORIZATION.md). Local full build remains outstanding; Java 21 present, Maven absent and GitHub DNS unavailable in execution environment.

FS-110A hosted CI 43611269131 passed 25 tests, four-module Maven reactor, checksum and dependency checks. PR #7 merged; local verification and FS-110B live verifier/authority integration pending. Wyrmgate source shows aud=clientId, not a separate storage resource audience; no arbitrary client login token accepted by design.

2026-11-15: PR #7 merged at 69d0ef978a5f04c283331851c00368632639de5c; final CI 43611373559 successful. Owner directed next. FS-110B verifier slice uses Nimbus JOSE+JWT and explicit trusted JWKS snapshots, tests real signed synthetic access tokens. Live IAM registration/key transport/authority store remain unconfigured. See [FS-110B](../security/FS110B_JWT_VERIFIER.md). FS-120 not started.

FS-110B verifier implementation hosted CI 43665624103 passed 31 tests (including 6 real-signature test methods), no failures/errors/skips. PR #9 merged; no live Wyrmgate registration, retrieval, binding or HTTP runtime. Local toolchain/DNS gap reconfirmed.

2026-11-15: Owner approved Kernel v0.3.2 dependency maintenance. PR #8 initially passed 18 tests against Central on the FS-100B baseline; rebased/merged with current FS-110A to preserve all security work. Fresh CI required on combined revision. No live verifier, DB or deployment claim.
PR #8 merged at 43f1c5552a3202ad0724e516fe51c03886373591. PR #9 conflict resolution preserves Kernel 0.3.2 Central resolution, FileReference length enforcement and JWT verifier. Previous FS-110B CI used Kernel 0.3.0; combined CI 43666318429 passed 31 tests.

2026-11-15: Owner requested Maven POM readability maintenance before the next implementation. Reformat all five POMs without changing XML values, element order, dependencies or build plugins; add lightweight formatting guard to make ci and editor guidance. No FS-120 authorization or runtime changes.

2026-11-15: PR #9 merged at 1ecf31bb4ebebbc3f89c90712a7de0ab53beb159; PR #10 merged at 37a49f3c2488d919ebfeb7f53933c552e499d91b with POM readability guard and 31 passing tests (43666571277). Owner directed next implementation: FS-120A only. Service-owned PostgreSQL schema and JDBC transaction adapter in review; no production migration or live IAM. See [persistence contract](../architecture/05_POSTGRESQL_PERSISTENCE.md). Kernel 0.3.2 remains baseline; FS-120B/C and live IAM remain open.

FS-120A code commit 7d64bf3868ea80bbccd4fb76c955968e7513b071 hosted CI 43667089332 passed 40 tests (9 on disposable PostgreSQL 17.12); migration V1 applied only to test database. PR #11 merged; local build unavailable, no production application or later increment authorization.

2026-11-15: PR #11 merged at b4a421dde33fc712a884f83b515719ef62d1c5be; final CI 43667199550 passed 40 tests, including 9 PostgreSQL. Owner reported merge in continuation of approved roadmap; next bounded FS-120B1 implements Kernel JDBC lease/atomic initiation/replay. FS-120B2 (configuration CAS and durable grants), FS-120C (audit/outbox/recovery) and live IAM remain open. No production migration. See [transactional initiation](../architecture/06_TRANSACTIONAL_INITIATION.md).

FS-120B1 hosted CI 43667699481 passed 46 tests, 15 on PostgreSQL 17.12. Kernel V1–V3 applied in disposable fs_kernel schema only. PR #12 open; local verification and owner review pending. B2/C, actual IAM and production gates remain open.

## Backlog baseline, 2026-11-15
PR #12 merged at `f28e9093105e5620e2b9a0eb8e36b2de6595c9f2`; final hosted CI [43667799363](https://github.com/vyrnforge/file-storage/actions/runs/43667799363) passed. This supersedes historical in-review statements above. The owner requested a complete issue backlog before further implementation. [Master issue #13](https://github.com/vyrnforge/file-storage/issues/13) links 37 work packages (#14–#50), with priorities, dependencies, acceptance evidence, owner decisions and suggested responsible disciplines. FS-120B2A [#20](https://github.com/vyrnforge/file-storage/issues/20) is the recommended next bounded increment; authorization pending. No runtime work, deployment or later scope approved by backlog creation.

2026-11-15: owner directed next implementation using Backend & Domain Engineering after PR #51 merged at d48e250c3bd289ff463a9fac44136979bc5c502c. FS-120B2A [#20](https://github.com/vyrnforge/file-storage/issues/20) authorized: V2 revision guards, internal Kernel JDBC transactional backend activation and policy publication; no live provider validation/admin API/production migration. [Configuration contract](../architecture/07_CONFIGURATION_REVISIONS.md). B2B #21 and later work require separate authorization.

2026-11-15: owner accepted PR #52 and explicitly authorized squash merge; main 32b387cd1b3ee2113832e4eff0ab224da53926d0, CI 43668967693 passed 51 tests (20 real PostgreSQL). Issue #20 closed, master #13 checked. Next authorized #21: durable grant read adapter and actual secured facade integration; see [grant contract](../security/FS120B2B_DURABLE_GRANTS.md). No production migration or live authority configuration; #22 separately gated.

2026-11-15: owner accepted continuation and merge of PR #53 at 137ebeab9a8e83b33219f9d3ae3ccc83f775abd1; CI 43669360900 passed 57 tests (26 PostgreSQL). #21 closed, #22 authorized. FS-120C1 uses actual Kernel transaction-bound audit/event writers for initiation, backend activation and policy publication; no dispatch/runtime or production migration. See [atomic intents](../architecture/08_ATOMIC_MUTATION_INTENTS.md).

2026-11-15: owner explicitly accepted and authorized merge of PR #54 at 271403d9eb968a2224f7cab963da486b3b0eca89; CI 43669852898 passed 61 tests (30 PostgreSQL). Issue #22 closed; #23 authorized. Internal operation ledger V3 and Kernel outbox delivery tick in review. [Recovery contract](../architecture/09_DURABLE_RECOVERY.md). No deployed worker, external transport or provider I/O; #24 separately gated.
