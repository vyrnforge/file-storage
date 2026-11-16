# Current state and legacy capability baseline

Date: 2026-11-15. Phase 2 FS-100A merged; FS-100B merged; FS-110A merged; FS-110B verifier merged; FS-120A merged; FS-120B1 merged (PR #12); FS-120B2A merged (PR #52); FS-120B2B merged (PR #53); FS-120C1 merged (PR #54); FS-120C2 merged (PR #55); FS-200A contract merged; DEC-FS012 approved; FS-200B S3 adapter merged (PR #59); FS-200C native OSS implementation merged (PR #60; actual staging pending); FS-210A registry in review. Java/Maven domain, provider SPI, S3/OSS adapters, application, security and persistence; no deployed service, production database, or released product. Legacy evidence: user-provided private src.zip (77 files; 13 Java test files), not a full Git checkout. Archive inspected statically; no build or tests executed. Do not publish the archive or private configuration.

## Legacy inventory (sanitized)
| Capability | Legacy evidence | Proposed treatment |
|---|---|---|
| Upload policy / completion | FileController, FileService, OssService | Preserve upload/completion outcomes; replace direct provider exposure with private transfer boundary |
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

2026-11-15: owner explicitly approved merge PR #55 at b5e8159843fb41121eeabe309497899c3bcd7257; CI 43670297365 passed 69 tests (38 PostgreSQL). Issue #23 closed; #24 authorized for provider-neutral contract and synthetic reusable tests. [Provider transfer contract](../architecture/10_PROVIDER_TRANSFER_CONTRACT.md). Private-location requirement remains binding; service-controlled transfer design proposed, owner decision and relevant #16 inputs pending. No SDK, cloud I/O, transfer runtime or production migration.

Owner approved DEC-FS012 on 2026-11-15: independent transfer gateway and separate API/worker runtime roles, optional private CDN acceleration. One product, repository, domain and authoritative metadata store; combined local/small deployment permitted. API authorizes/orchestrates, never carries consumer file bodies in separated profile. Gateway streams to private providers, workers verify/scan/reconcile. No direct provider URL, locator, signature or credential exposure. CDN conditional, no shared confidential caching by default. No production deployment or limits approved.
#25 authorized conditional on test-profile prerequisites; #57/#58 tracked, no runtime deployment.

2026-11-15: owner confirmed both PRs reviewed. PR #56 squash merged at c2c3c9a8a5f18a6d66f859097ae3031ec73bb508; CI 43673922475 passed 78 tests. #25 isolated MinIO profile verified by CI 43675256063 (91 tests, including 13 real MinIO and 38 PostgreSQL; zero failures/errors/skips). PR #59 reconciled with merged baseline; final compatible CI required. No production target compatibility, deployed gateway or later authorization. #26 next candidate only.

2026-11-15: PR #59 accepted and merged at e567b2fc4df8ac4fcdcdde549c02cea3eaab9e71; final CI 43675532564 passed 91 tests (13 real MinIO, 38 PostgreSQL). #25 closed. Owner authorized #26; native SDK and loopback wire fixture, no real OSS staging/legacy full-build proof; #26 remains gated on those prerequisites. [OSS profile](../architecture/12_OSS_ADAPTER_PROFILE.md).

2026-11-15: owner authorized PR #60 merge and #27; squash 972e091004725f1d3eef11d550fa773e4897545e; final CI 43676549629 passed 103 tests. #26 remains open for OSS staging and #14 parity. #27 internal registry/credential reference port in review; actual secret manager, workload identity, endpoint allowlist, live authorization and provider validation remain pending. [Registry contract](../architecture/13_BACKEND_REGISTRY.md).

2026-11-15: Owner accepted and authorized merge of PR #61 and next #28. FS-210A merged at `224eada5824b1ae807136e3b28fd6fefb668ccda`; exact-head CI 43678260351 passed 108 tests. #27 remains open for live credential authority/evidence (#16). FS-210B internal validation boundary, explicit drain CAS and guarded retirement with V4 migration in review; no actual provider validator, later location admission, deployment or secret manager selection. First drain CI 43681632900 passed; expanded exact-head CI pending. See [backend lifecycle](../architecture/14_BACKEND_LIFECYCLE.md).

2026-11-15: Owner accepted and merged PR #62 at a4d9e198aa74b0048f82ff3ff6a93560215b9ac1; CI 43689433560 verified 117 tests. V4 tested only on disposable PostgreSQL. #28 remains open for concrete provider validation, #16 authority/evidence lifetime and late location admission. #29 FS-220 authorized; internal policy authority, reads, publication and rollback in review. No public admin or production runtime. See [policy control](../architecture/15_POLICY_CONTROL_PLANE.md).

2026-11-15: Owner accepted continuation; PR #63 merged at 1d055345e060bac1283a2e03924cf21bd601c88e; CI 43690547948 verified 121 tests. #30 FS-300A isolated runtime foundation authorized, in review; health-only executable, proposed v1 secured routes tested only in isolation, no live authority or production deployment. See [runtime foundation](../architecture/16_RUNTIME_FOUNDATION.md).

2026-11-15: PR #64 accepted and merged at 6ed88c1e5cc4a551f39103a491e1472514e4d666; CI 43691154932 passed 125 tests. #30 continuation: explicit API role composition, cleaner HTTP packages, bounded metadata admission and neutral errors in review. #65 owner quality requirement tracked; live IAM, actual readiness/control-plane routes and gateway remain open.

2026-11-15: Owner accepted and merged PR #66 at 151898682db583317718a080415deff74992e4b7; CI 43692244128 passed 129 tests. #30 continuation: isolated policy admin HTTP, separate action authority and shared metadata admission, real PostgreSQL contract tests in review. See [policy administration](../architecture/17_POLICY_ADMIN_HTTP.md). No live authority or deployment.

2026-11-15: PR #67 accepted/merged c08dd44250e0253f0b9a83a81bd679986473159e; CI 43693427687 passed 133 tests. #30 continuation: isolated backend administration HTTP and real DB contracts in review. See [backend administration](../architecture/18_BACKEND_ADMIN_HTTP.md). No live platform authority or provider validation evidence; #16 #17–#19 #26–#28 remain open.

2026-11-15: PR #68 owner approved and merged 4c61e167f007e0ba14bff25ab71e037df2e9a221; CI 43694052093 passed 137 tests. Owner requested dependency-first delivery; skip externally blocked work, fix independent software gaps. #28 latest-only draft safety in review; #30 isolated runtime composition/readiness next. Actual IAM/provider evidence remains blocked.

2026-11-15: PR #69 approved/merged 28f66a0a38cd5fabc560de9787cb33f2f51cbafd; final CI 43696097585 passed 139 tests after fixing seeded audit/event baseline assertion. #28 independent superseded draft defect resolved; actual provider gates remain. #30 explicit API composition and readiness in review. See [runtime composition](../architecture/19_RUNTIME_COMPOSITION.md).

2026-11-15: #30 composed runtime PR #70 first CI failed missing test Action import; corrected head 0e4fd2c passed 142 tests (43697253366). Final isolated contract/readiness revision adds strict metadata JSON, registered v1 route parity and actual wire-schema tests; final CI pending. No live exposure or #57 implementation.

2026-11-15: PR #70 owner approved and merged a0626181d75bac947c51bb154e0203a83a76a773; exact-head CI 43697561531 passed 143 tests. Isolated #30 accepted, live dependencies deferred. #57 isolated gateway authorized, first provider-neutral role/streaming/claim boundary in review, synthetic authority/provider only. See [gateway](../architecture/20_PRIVATE_TRANSFER_GATEWAY.md). No #31/#35 or production exposure.

2026-11-15: PR #71 owner reviewed/merged 270fd099f19ade86eb4f51cdeea0ac6a2f3dddad; CI 43698807779 passed 148 tests. #57 streaming boundary accepted. Durable initial upload claim V5 and explicit UPLOAD_TRANSFER authorization in review; real DB tests with synthetic identity. [Durable upload transfer](../architecture/21_DURABLE_UPLOAD_TRANSFER.md). No provider integration or production migration.

2026-11-15: PR #72 approved/merged 4880a42b941c0d3dfb1979ad34f8a00220bfa953; CI 43699722472 passed 152 tests. #57 durable upload authorization accepted. Registry-pinned adapter integration and real PostgreSQL/MinIO HTTP upload in review; native OSS gateway integration and verified lifecycle pending. [Registry provider integration](../architecture/22_REGISTRY_PROVIDER_TRANSFER.md).

2026-11-15: PR #73 approved/merged 771ed70c2881e7ae0cba033f19dd5f20b8e86443; CI 43700750262 passed 154 tests. Real PostgreSQL/MinIO staged upload accepted. #57 native OSS loopback and combined API/transfer runtime integration in review. [Evidence scope](../architecture/23_OSS_COMBINED_GATEWAY.md).

2026-11-15: PR #74 approved/merged cbc61eff70a98243eb807e0a5f3130ca7da61acd; CI 43702277359 passed 155 tests. #57 isolated native OSS/combined integration accepted; live gates deferred. #31 authorized: V6 durable integrity request and independently measured matching evidence in review, no AVAILABLE transition. [Upload integrity](../architecture/24_UPLOAD_INTEGRITY.md).

2026-11-15: PR #75 approved/merged 15015ec1c555f80ef761e444c57dc148eb7fbb05; CI 43703039804 passed 157 tests. #31 durable integrity accepted. V7 isolated scan lifecycle and operation lease fencing in review. [Scan lifecycle](../architecture/25_UPLOAD_SCAN_LIFECYCLE.md). Synthetic scanner only; no production availability or deployment.

2026-11-15: PR #76 approved/merged 05b6b8d87b69c2af37ff4ec6492d2ae34f44fac9; CI 43704441936 passed 165 tests. #31 V7 scan lifecycle accepted; isolated completion commands and V3 durable verification worker in review. [Completion API/worker](../architecture/26_COMPLETION_API_WORKER.md). No production availability claim.

2026-11-15: PR #77 approved/merged 4f65a0d812a95990c668cedcf5f195f34a7acd59; CI 43706027945 passed 168 tests. #31 isolated completion HTTP/worker accepted. V8 terminal replay and logical recovery in review; physical cleanup HOLD pending quiescence evidence. [Recovery](../architecture/27_TERMINAL_UPLOAD_RECOVERY.md).

2026-11-15: PR #78 approved/merged edb185caa07332de52e84daabba2e201e41b998a; CI 43707217512 passed 173 tests. #31 terminal recovery accepted, cleanup HOLD remains. #35 V9 authorized download in review. [Download](../architecture/28_AUTHORIZED_DOWNLOAD.md).

2026-11-15: PR #79 approved/merged b8450b3c9b046138353c400b86218c43cc485185; CI 43708868214 passed 177 tests. #35 isolated upload-to-download accepted. V10 scoped metadata catalog/usage in review. [Catalog](../architecture/29_METADATA_CATALOG.md).


2026-11-15: Owner approved PR #80, squash merged `02814b4ae0d2c596be56b798067e7b0b696c8d84`; exact-head CI 43709875026 passed 180 tests. Owner authorized #36 FS-310A1 reversible logical lifecycle only. V11 candidate in review; retention/hold, quota, physical purge, production migration and deployment remain gated. See [FS-310A1 contract](../architecture/30_LOGICAL_FILE_LIFECYCLE.md).


2026-11-15: PR #81 accepted/squash merged `660f14b84c3a6f2bbf15b7d2157189cf5d865fb4`; exact-head CI 43710854973 passed 183 tests. Owner authorized #36 FS-310A2 independent safety/recovery and conservative retention assessment only. V12 candidate; [contract](../architecture/31_LIFECYCLE_SAFETY.md). No production policy, physical purge, quota or deployment.


2026-11-16 (Asia/Jakarta): PR #82 accepted/squash merged `a1fd681bd0dab49f13f14418082b9d365a2a86ee`; exact-head CI 43712395352 passed 185 tests. Owner authorized #36 FS-310A3 durable purge preparation only; V13 candidate, [contract](../architecture/32_PURGE_PREPARATION.md). Actual provider deletion, production retention/hold/quota policy and deployment remain gated.


2026-11-16 (Asia/Jakarta): PR #83 accepted/squash merged `65b6ec440cea7ec0ab30303bbdcb335c8f3952b2`; exact-head CI 43779802955 passed 187 tests. Owner authorized #36 FS-310A4 durable reader safety and simulated execution only. V14 candidate, [contract](../architecture/33_READER_SAFETY_SIMULATION.md). No actual provider deletion, production retention/hold/quota policy or deployment.


2026-11-16 (Asia/Jakarta): PR #84 accepted/squash merged `0d0cc026f90954df554a1d8fa7f99045e1ecbff0`; exact-head CI 43781101800 passed 190 tests. Owner authorized #36 FS-310A5 governed policy/reconciliation foundations. V15 candidate, [contract](../architecture/34_GOVERNED_LIFECYCLE.md); [owner decisions](11_RETENTION_QUOTA_REVIEW.md). No production policy activation, actual provider deletion or deployment.


2026-11-16 (Asia/Jakarta): PR #85 accepted/squash merged `8ea998faf20d0f94ac92f6a5ddb083e994cae592`; exact-head CI 43782219424 passed 193 tests. Owner authorized #36/#33 FS-310A6 isolated policy and quota primitives. V16 candidate, [contract](../architecture/35_ISOLATED_POLICY_QUOTA.md). No production activation, limits/durations, hold release, actual provider deletion or deployment.


2026-11-16 (Asia/Jakarta): PR #86 accepted/squash merged 4af51dd819498d323345475d4a9cc3bf16d28288; exact-head CI 43785045045 passed 197 tests. Owner authorized FS-310A7 isolated transactional quota integration and reconciliation (#13/#16/#33/#35/#36). No production policy, historical backfill, hold release, actual provider deletion or deployment. PR #87 excluded.
