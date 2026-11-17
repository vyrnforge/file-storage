# Delivery roadmap and initial backlog

Approved architecture and roadmap direction began 2026-11-14; the following work-package table is the **historical approved plan**, not proof that every item was applied or verified. At 2026-11-17, the isolated backend reached merged `main` `a1903778af1e272ae15c025fe28c4cca369c09af` (234 Java tests passed in CI 44010497592), but quality audit and independent integration/release gates remain open. Owner approved **QC-00 quality governance reconciliation and QC-01 test-only characterization/suite organization**; feature work and code remediations are held. See [current quality state matrix](15_QUALITY_RECOVERY_CONTROL.md), reopened [#65](https://github.com/vyrnforge/file-storage/issues/65), draft [audit #108](https://github.com/vyrnforge/file-storage/pull/108) and [review #115](https://github.com/vyrnforge/file-storage/pull/115). Master delivery tracker [#13](https://github.com/vyrnforge/file-storage/issues/13) remains open; P0 release-blocking, P1 enterprise scope, P2 follow-up.

| ID | Phase | Priority | Deliverable / acceptance |
|---|---|---|---|
| FS-000 | 0 | P0 | Approve scope, ADRs, stack, legacy reuse/compatibility, deployment targets; owner sign-off |
| FS-001 | 0 | P0 | Obtain full private legacy build evidence; characterize API/tests and data migration requirements; sanitized report |
| FS-002 | 0 | P0 | Approve repo governance, license, maintainers, CI check and security reporting |
| FS-100 | 1 | P0 | Domain/placement model and provider SPI; prove stable IDs and pinned sessions |
| FS-110 | 1 | P0 | IAM/kernel context + tenant/file authorization; isolation tests |
| FS-120 | 1 | P0 | Metadata migrations + audit/idempotency/outbox; crash/retry tests |
| FS-200 | 2 | P0 | S3 and OSS adapter contract tests; supported capability matrix |
| FS-210 | 2 | P0 | Backend/location registry and secret refs; validated activation/drain/retire |
| FS-220 | 2 | P0 | Policy routing; switch new writes without changing old reads |
| FS-300 | 3 | P0 | Upload/download, batch/session/quotas, checksum/scan; legacy regression |
| FS-310 | 3 | P0 | Delete/restore/purge, recovery and bounded reconciliation |
| FS-400 | 4 | P1 | Admin console via Shared UI; config/operations/audit workflows |
| FS-500 | 5 | P0 | Production hardening: threat tests, load, backups/restore, alerts/runbooks |
| FS-600 | 6 | P1 | Legacy migration if data exists; dry run, preserve IDs, reconcile, rollback |
| FS-700 | 7 | P1 | Asset and ITSM consumer contract tests and SDK; no direct storage access |
| FS-800 | 8 | P0 | Production acceptance, independent deployment/release and owner approval |
| FS-810 | Later | P2 | Optional kernel client adapter decision based on real usage |

Security/reliability built into each phase, not deferred to Phase 5. Sequence may change after audit. No dates/estimates until requirements and team capacity established. For each task record Specified / Implemented / Applied / Verified separately, evidence links and remaining blockers. Phase 0 exit: owner approves explicit ADRs and roadmap; then create implementation issues.

## First increment refinement (historical record)
FS-100A ([scope and acceptance](07_FIRST_IMPLEMENTATION_INCREMENT.md)) splits FS-100 into pure domain/placement and provider SPI foundations; FS-100B follows with service composition and persistence contract design. FS-100A sequencing refinement explicitly approved; FS-100B merged. FS-002 repository rules and CI are partly verified; administrative security readback, licensing and remaining enforcement evidence stay open.

FS-100A: PR #5 merged, hosted final CI passed, local verification outstanding. FS-100B: [outcome and rules](09_PRODUCT_OUTCOMES_AND_RULES.md), [acceptance](10_FS100B_ACCEPTANCE.md); application foundation merged (PR #6). FS-110A merged; FS-110B cryptographic verifier in review, live deployment contract blocked. FS-120 not authorized.

FS-110 is split into A (Kernel context, binding/entitlement/domain authorization and negative tests) and B (actual credential verifier, authoritative binding/grant integration, runtime endpoint wiring and environment verification). A does not close B or the overall FS-110 production gate.

FS-110A: hosted synthetic acceptance I01–I08 passed (25 total tests) in run 43611269131; merged PR #7; local verification pending. FS-110B: actual resource audience/authorized client binding and verifier integration not yet resolved. See security contract for precise source findings; FS-120 remains unstarted.

2026-11-15: FS-110A PR #7 merged; FS-110B verifier slice authorized by owner continuation. Verify real RSA signatures, dedicated audience/client profile and trusted key rotation snapshots. Live registration, JWKS transport and authoritative bindings/grants remain open; FS-110 overall production gate not closed. FS-120 not authorized.

FS-110B verifier slice implemented on PR #9; hosted CI 43665624103 passed 31 tests. Remaining FS-110B deployment/integration gates: exact intended audience/client registration, live JWKS transport/rotation, authoritative identity/bindings/grants and machine/delegation, runtime wiring and staging evidence. Do not mark overall FS-110 complete. FS-120 separate approval.

PR #8 Kernel 0.3.2 maintenance merged; PR #9 synchronized with main. Combined CI required; historical 0.3.0 evidence not a substitute.

2026-11-15: FS-120A authorized after PR #10 merge. Schema and JDBC adapter for existing initiation/read use cases, disposable PostgreSQL acceptance. FS-120B: Kernel executeIdempotent, activation CAS/concurrency and durable grant integration. FS-120C: Kernel audit/outbox, durable work/recovery. No production migrations or live IAM closure. [FS-120A contract](../architecture/05_POSTGRESQL_PERSISTENCE.md).

FS-120A code CI 43667089332: 40 tests passed, 9 real PostgreSQL tests, five modules and six readable POMs. PR #11 merged; local verification outstanding. FS-120B1 in review; B2/C not started.

FS-120B split into B1 (Kernel lease, atomic initiation and replay, concurrency/failure tests) and B2 (activation CAS, durable grant retrieval). B1 is the next bounded increment after PR #11 merge. FS-120C audit/outbox integration remains separate; no external I/O or public API.

FS-120B1: PR #12 implementation verified by hosted CI 43667699481 (46 tests, zero failures); local verification and owner review/merge pending. B2/C not started.

## Executable backlog — 2026-11-15
Master tracker [#13](https://github.com/vyrnforge/file-storage/issues/13) is the live work-package checklist for the approved roadmap. Its 37 linked issues (#14–#50) contain the detailed scope, dependencies, acceptance criteria and suggested responsible disciplines. This planning decomposition does not expand approved scope or authorize implementation, production application, release or cross-repository changes. PR #12 merged at `f28e9093105e5620e2b9a0eb8e36b2de6595c9f2`; final CI [43667799363](https://github.com/vyrnforge/file-storage/actions/runs/43667799363) passed (46 tests at the verified code head). Historical in-review statements above are superseded.

| Workstream | Issues | Gate |
|---|---|---|
| Product decisions, legacy, governance, IAM | #14–#19 | Owner/security evidence and live IAM contract; parallel to isolated persistence work |
| Persistence/reliability | #20–#23 | CAS then durable grants, atomic audit/events, durable recovery |
| Providers/control plane | #24–#29 | Privacy/provider contract, tested adapters, registry and validated policy switching |
| Runtime/data plane | #30–#35 | HTTP contract and live IAM gate; integrity and quota before upload completion |
| Lifecycle | #36–#38 | Retention, bounded cleanup, verified migration |
| Console | #39–#41 | Shared UI and actual authorized service contracts |
| Enterprise operations | #42–#44 | Deployment configuration, threat tests, recovery/capacity evidence |
| Legacy and consumers | #45–#48 | Conditional legacy migration; SDK then Asset/ITSM staging acceptance |
| Release and optional follow-up | #49–#50 | Owner release approval; optional Kernel adapter only after proven demand |

Recommended next authorization: FS-120B2A [#20](https://github.com/vyrnforge/file-storage/issues/20). Record each implementation PR against its stable FS ID and issue number, test the issue acceptance criteria against compatible revisions, record gaps separately, and close only after evidence and owner acceptance. Track Specified / Implemented / Applied / Verified distinctly. No fixed dates, named-person assignments or production SLO commitments from this decomposition. Issue #13 remains open until the enterprise release acceptance is complete; conditional #50 may be deferred explicitly.

2026-11-15: FS-120B2A [#20](https://github.com/vyrnforge/file-storage/issues/20) authorized and in review after PR #51 merge. Scope and acceptance in [configuration revisions](../architecture/07_CONFIGURATION_REVISIONS.md). B2B #21 and later increments remain pending. Prior next-candidate language is historical.

2026-11-15: #20 accepted, PR #52 squash merged at 32b387cd1b3ee2113832e4eff0ab224da53926d0 (51 hosted tests). #21 authorized, durable grant read adapter only; authoritative issuance and live IAM remain blocked on #17/#19. #22 is next candidate after owner acceptance; no automatic authorization.

2026-11-15: #21 accepted and PR #53 merged at 137ebeab9a8e83b33219f9d3ae3ccc83f775abd1; #22 authorized for atomic mutation audit/outbox intents. #23 dispatch, jobs and recovery remains separately gated. Read/denial audit policy and live IAM remain open.

2026-11-15: #22 accepted and merged at 271403d9eb968a2224f7cab963da486b3b0eca89; #23 authorized for internal worker foundation. #24 provider/privacy contract is next candidate, subject to explicit owner approval and open #16 decisions. Actual provider operation handlers and broker integration remain later work.

2026-11-15: #23 accepted and PR #55 merged at b5e8159843fb41121eeabe309497899c3bcd7257. #24 provider contract and tests authorized; owner/security transfer decision and relevant #16 product/profile choices remain pending. #25/#26 require separate approval and real profile evidence; no provider-SDK implementation in #24.

## DEC-FS012 delivery refinement (2026-11-15)
Owner approved DEC-FS012 on 2026-11-15: independent transfer gateway and separate API/worker runtime roles, optional private CDN acceleration. One product, repository, domain and authoritative metadata store; combined local/small deployment permitted. API authorizes/orchestrates, never carries consumer file bodies in separated profile. Gateway streams to private providers, workers verify/scan/reconcile. No direct provider URL, locator, signature or credential exposure. CDN conditional, no shared confidential caching by default. No production deployment or limits approved.
#57 FS-300G private transfer gateway is a P0 foundation after #30 and adapters/registry, before #31/#35. #58 FS-500D CDN acceleration is P2 conditional on #35/#42–#44 and approved content/cache policy. #30 composes roles, #42 deploys independently, #43 tests trust/cache isolation, #44 measures API latency under load, TTFB, throughput, origin traffic and cost/GB. #16 production choices remain open. #25 isolated profile may proceed once prerequisites resolved; no blanket production compatibility or merge authorization.

2026-11-15: #24 accepted and merged at c2c3c9a8a5f18a6d66f859097ae3031ec73bb508; #25 reviewed, 91 tests passed in CI 43675256063, reconcile and verify final head before merge. #26 remains next candidate pending separate authorization and native OSS profile prerequisites.

2026-11-15: #25 accepted and merged at e567b2fc4df8ac4fcdcdde549c02cea3eaab9e71; #26 authorized for native OSS adapter and local SDK wire tests. Actual OSS staging, #14 full legacy parity and owner acceptance remain gates; #27 separately gated.

2026-11-15: #26 implementation merged at 972e091004725f1d3eef11d550fa773e4897545e; actual OSS staging and #14 parity remain open. #27 internal registry/reference port authorized, #16 secret manager/workload identity decision and #28 provider validation still gates. No later implementation or deployment approved.

FS-210A #27 internal implementation accepted and merged PR #61 (`224eada`); live credential evidence remains open. Owner authorized next #28; internal validation boundary, drain and guarded retirement (V4) in review; concrete provider validation/authority and late location admission pending. See [backend lifecycle](../architecture/14_BACKEND_LIFECYCLE.md).

FS-210B internal lifecycle accepted and merged PR #62 (a4d9e19), 117 tests verified; actual provider evidence and later location admission remain open. Owner authorized #29 FS-220 bounded internal policy management (also called FS-210C); live authority and #30 remain gated. See [policy control](../architecture/15_POLICY_CONTROL_PLANE.md).

FS-220 #29 internal policy accepted and merged PR #63 (1d05534), 121 tests. #30 FS-300A isolated runtime foundation authorized; actual IAM #17–#19, transfer #57, later #31 and deployment separately gated. See [runtime foundation](../architecture/16_RUNTIME_FOUNDATION.md).

FS-300A foundation accepted/merged PR #64 (6ed88c1), 125 verified tests. Owner authorized isolated HTTP boundary refinement under #30; #65 engineering quality tracks incremental cleanup and proposed dedicated review before console expansion. #57 and production gates remain separate.

FS-300A HTTP refinement accepted/merged PR #66 (1518986), 129 verified tests. Owner authorized #30 isolated policy administration HTTP slice; #65 incremental cleanup remains active, actual IAM and #57 separately gated.

FS-300A policy administration accepted/merged PR #67 (c08dd44), 133 verified tests. Owner authorized #30 backend admin HTTP slice; server-owned target policy/validator and explicit platform permission, isolated tests only. #57 and deployment separately gated.

PR #68 accepted and merged 4c61e16; 137 verified tests. Owner directed dependency-first delivery. #28 independent superseded draft safety before #30 isolated runtime composition/readiness; #14/#16/#17 external inputs and #18/#19/#26/#27 live integration deferred without dropping gates. #57 remains next after isolated #30.

PR #69 accepted/merged 28f66a0; 139 verified tests. #28 latest-only validation/activation guard complete, concrete evidence still gated. #30 isolated runtime composition/readiness in review; #57 next candidate after acceptance, live integration separately gated.

PR #70 accepted/merged a062618; 143 tests. Isolated #30 complete; owner authorized #57 isolated gateway foundation. First slice explicit authority/resolver/limits and transfer-only HTTP roles, then durable claim and real adapter integration; actual IAM, provider evidence and production limits remain gated. #31/#35 separately scoped.

PR #71 accepted/merged 270fd09; 148 verified tests. #57 continuation: V5 durable initial upload handle and explicit permission, one attempt per session, no automatic reissue; then registry provider resolution/real adapter gateway tests. Download availability #31/#35 and production decisions separately gated.

PR #72 accepted/merged 4880a42 (152 verified tests). #57 continuation exact registry pin/adapter leases and actual MinIO gateway upload; then native OSS gateway fixture and #31 verified completion/reconciliation, #35 download. Live authority/provider/production gates deferred.

PR #73 accepted/merged 771ed70 (154 tests). #57 isolated native OSS gateway and combined runtime integration next, then #31 verification/completion; actual OSS, IAM, limits and deployment gates deferred.

PR #74 accepted/merged cbc61ef (155 verified tests); isolated #57 accepted. Owner authorized #31: first V6 integrity evidence and read-only reconciliation; next durable verification/scan lifecycle; #32 scanner decisions, #33 quota, #35 download and live/deployment gates remain separate.

PR #75 accepted/merged 15015ec (157 verified tests). #31 next V7 durable scan queue, trusted exact-content verdict and lifecycle CAS; then completion HTTP, worker scheduling/expiry/reconciliation and quota #33. #32 production scanner/content policy and #35 download remain gated.

PR #76 accepted/merged 05b6b8d (165 verified tests). #31 next isolated upload commands, durable verification and scan handoff, opt-in worker scheduling; terminal reconciliation/reissue/cleanup remains, #32/#33 production policy and #35 download separate.

PR #77 accepted/merged 4f65a0d (168 tests). #31 next terminal replay and logical expiry/dead recovery; physical cleanup/reissue require remote-write quiescence evidence and quota policy, then independent #35 authorized download. Live gates deferred.

PR #78 accepted/merged edb185c (173 tests); #31 logical recovery accepted, physical cleanup/reissue gated. #35 next exact clean-version download through private gateway; pagination/usage and deleted-state lifecycle separate.

PR #79 accepted/merged b8450b3 (177 tests). #35 next scoped catalog/usage; #36 logical deletion/retention follows, quota/physical purge/cleanup gated.


2026-11-15: PR #81 accepted/squash merged `660f14b84c3a6f2bbf15b7d2157189cf5d865fb4`; exact-head CI 43710854973 passed 183 tests. Owner authorized #36 FS-310A2 independent safety/recovery and conservative retention assessment only. V12 candidate; [contract](../architecture/31_LIFECYCLE_SAFETY.md). No production policy, physical purge, quota or deployment.


2026-11-16 (Asia/Jakarta): PR #82 accepted/squash merged `a1fd681bd0dab49f13f14418082b9d365a2a86ee`; exact-head CI 43712395352 passed 185 tests. Owner authorized #36 FS-310A3 durable purge preparation only; V13 candidate, [contract](../architecture/32_PURGE_PREPARATION.md). Actual provider deletion, production retention/hold/quota policy and deployment remain gated.


2026-11-16 (Asia/Jakarta): PR #83 accepted/squash merged `65b6ec440cea7ec0ab30303bbdcb335c8f3952b2`; exact-head CI 43779802955 passed 187 tests. Owner authorized #36 FS-310A4 durable reader safety and simulated execution only. V14 candidate, [contract](../architecture/33_READER_SAFETY_SIMULATION.md). No actual provider deletion, production retention/hold/quota policy or deployment.


2026-11-16 (Asia/Jakarta): PR #84 accepted/squash merged `0d0cc026f90954df554a1d8fa7f99045e1ecbff0`; exact-head CI 43781101800 passed 190 tests. Owner authorized #36 FS-310A5 governed policy/reconciliation foundations. V15 candidate, [contract](../architecture/34_GOVERNED_LIFECYCLE.md); [owner decisions](11_RETENTION_QUOTA_REVIEW.md). No production policy activation, actual provider deletion or deployment.


2026-11-16 (Asia/Jakarta): PR #85 accepted/squash merged `8ea998faf20d0f94ac92f6a5ddb083e994cae592`; exact-head CI 43782219424 passed 193 tests. Owner authorized #36/#33 FS-310A6 isolated policy and quota primitives. V16 candidate, [contract](../architecture/35_ISOLATED_POLICY_QUOTA.md). No production activation, limits/durations, hold release, actual provider deletion or deployment.


2026-11-16 (Asia/Jakarta): PR #86 accepted/squash merged 4af51dd819498d323345475d4a9cc3bf16d28288; exact-head CI 43785045045 passed 197 tests. Owner authorized FS-310A7 isolated transactional quota integration and reconciliation (#13/#16/#33/#35/#36). No production policy, historical backfill, hold release, actual provider deletion or deployment. PR #87 excluded.


FS-310A7 candidate PR #88: [full CI 43801908520](https://github.com/vyrnforge/file-storage/actions/runs/43801908520) passed 199 tests (zero failures/errors/skips) at implementation head `74d22283d2254d482254b19fb68e92e3c8803dcf`; V17 repeatability and two new PostgreSQL regressions. Owner review/merge pending. Final review head verification is recorded on PR #88. Engineering choices: explicit empty isolated tenant, durable tenant fence even for non-enabled initiation (contention/load evidence deferred), transaction-triggered ledger changes and read-only reconciliation. No production activation or deployment.


2026-11-16 (Asia/Jakarta): PR #88 accepted/squash merged 453cc93732bfac19e2b1dbffec3a6737af690a0e; exact-head CI 43802227997 passed 199 tests. Owner authorized FS-310A8 governed quota transitions and reconciliation safety (#13/#16/#33/#35/#36). V18 isolated candidate, independently reviewed quota limit/TTL and immutable dry-run findings. No production policy/limits, hold release, actual historical backfill, provider deletion or deployment. PR #87 excluded.


FS-310A8 candidate PR #91: [implementation CI 43803914425](https://github.com/vyrnforge/file-storage/actions/runs/43803914425) BUILD SUCCESS at `fb89dec83148525ace1c6c258e7cd3898b91358e` against main 453cc93: 203 tests, zero failures/errors/skips (58 persistence, 80 runtime, 40 domain/application/SPI/security, 13 S3/MinIO, 12 OSS). V18 migration repeatability and four new PostgreSQL regressions passed. Initial CI 43803640155 was superseded/cancelled; hardened CI 43803742192 failed V18 enablement guard dollar quoting and disposable migration rolled back; corrected full suite passed, no tests removed. Final review-head evidence recorded on PR #91. Owner review/merge pending. New initial and replacement activations require independently reviewed exact limit/TTL; new integration enablement requires reviewed activation. Historical rows retained; no actual historical backfill, production policy, hold release, provider deletion or deployment.


2026-11-16 (Asia/Jakarta): PR #91 accepted/squash merged `11c1a5ead150ec9f77ffd8225f0c070ad154bca3`; exact-head CI 43804275443 passed 203 tests. Owner authorized FS-310A9 (#13/#16/#33/#35/#36), V19 isolated dry-run manifest and restricted execution foundation. [Contract](../architecture/38_HISTORICAL_ACCOUNTING_MANIFEST.md). CI and owner review pending. No actual historical backfill, production policy/limits/durations, hold release, provider deletion or deployment. PR #87 excluded.


2026-11-16 (Asia/Jakarta): owner approved PR #92; squash merged `1419360d456079f3ffe3510babdab6eb721302f1`; [exact-head CI 43806355043](https://github.com/vyrnforge/file-storage/actions/runs/43806355043) passed 205 tests. FS-310A10 selected as next isolated increment: restricted lifecycle completion/recovery and atomic Kernel manifest command receipts. [Contract](../architecture/39_RESTRICTED_LIFECYCLE_AUDIT.md). CI and owner review pending. Production retention/limits, legal-hold release, actual historical backfill, provider deletion and deployment remain gated; PR #87 excluded.


2026-11-16 (Asia/Jakarta): owner approved PR #94; squash merged `69c77b2803f239e255baef6a1ba652f00616ff40`; [exact-head CI 43821134184](https://github.com/vyrnforge/file-storage/actions/runs/43821134184) passed 207 tests. FS-310A11 authorized isolated execution-boundary hardening: V21 audited principal mode and deferred raw-bypass protection, lock-only identity guards, narrower worker settlement privileges and concurrent completion/recovery regression. [Contract](../architecture/40_EXECUTION_BOUNDARY.md). CI and owner review pending. No actual historical backfill, production retention/limits, hold release, provider deletion or deployment; PR #87 excluded.


2026-11-16 (Asia/Jakarta): owner approved PR #96; squash merged `c1f4160c26d7ac0c2f74e5c129c7cb28ea093b10`; [exact-head CI 43823451203](https://github.com/vyrnforge/file-storage/actions/runs/43823451203) passed 209 tests. FS-310A12 authorized: versioned isolated role profiles, effective privilege drift/revocation, bounded quota/manifest workload and logical restore rehearsal, production readiness checklist. [Contract](../architecture/41_ROLE_OPERATIONAL_READINESS.md). No new migration or production roles. CI and owner review pending. No historical backfill, production limits/durations, hold release, provider deletion or deployment; PR #87 excluded.


2026-11-16 (Asia/Jakarta): owner approved PR #97; squash merged `fb606a7698df1bbef789efd51732178867ebaa95`; [exact-head CI 43824603192](https://github.com/vyrnforge/file-storage/actions/runs/43824603192) passed 212 tests. FS-310A13 authorized: scoped metadata/transfer/backend administration profiles, V22 lock-only identities, extended privilege drift, isolated credential rotation and quarantined restore/dispatcher restart, staging approval package. [Contract](../architecture/42_RUNTIME_ROLE_ACTIVATION.md). CI and owner review pending. No production provisioning, historical backfill, production limits/durations, hold release, provider deletion or deployment; PR #87 excluded.


2026-11-16 (Asia/Jakarta): owner approved PR #98; squash merged `8e48e5ad9c6789b8b7e11ca858b185f56eb92630`; [exact-head CI 43826187384](https://github.com/vyrnforge/file-storage/actions/runs/43826187384) passed 215 tests. FS-310A14 authorized: separated API/internal adapter sources, V3 scoped roles, V23 retention evidence lock identity, fail-closed identity/readiness and HTTP integration, staging go/no-go package. [Contract](../architecture/43_SEPARATED_RUNTIME_STAGING.md). CI and owner review pending. Staging deployment not approved; production gates closed; PR #87 excluded.


2026-11-16 (Asia/Jakarta): owner approved PR #100; squash merged `5ea7608abd0961361359035be742d24e93cd0a1e`; [exact-head CI 43829959343](https://github.com/vyrnforge/file-storage/actions/runs/43829959343) passed 216 tests. FS-310A15 authorized: explicit staging runtime with eleven separated identities, exact provider pin/ref admission, bounded verification/scan/recovery and gated dispatcher, packaged S3/OSS adapters, runtime inventory and staging runbook. [Contract](../architecture/44_STAGING_OPERATIONS.md). CI and owner review pending. No deployment, production policy, historical backfill, hold release or physical deletion; PR #87 excluded.


2026-11-16 (Asia/Jakarta): owner approved PR #101; squash merged `e8b15d4fe5158daccb2c20acf4707ba3a00b3b63`; [exact-head CI 43833409866](https://github.com/vyrnforge/file-storage/actions/runs/43833409866) passed 217 tests. FS-310A16 authorized: strict staged bootstrap and mounted secret/TLS role sources, Wyrmgate RS256 snapshot readiness, concrete reviewed S3/OSS factories, CycloneDX runtime SBOM and unsigned artifact-bound review evidence, rollback-only role plan and admission evidence form. [Contract](../architecture/45_STAGING_BOOTSTRAP.md). CI and owner review pending. V23 retained; actual environment integrations, vulnerability/license disposition, signed provenance and deployment authorization outstanding. No deployment, production policy, historical backfill, hold release or provider deletion; PR #87 excluded.


2026-11-16 (Asia/Jakarta): owner approved A16 PR #103 as engineering/release-evidence baseline only; squash merged `b9420b5d4ef507be32328040b5ea11ad3c330308`; [exact-head CI 43837282298](https://github.com/vyrnforge/file-storage/actions/runs/43837282298) passed 222 tests, 126 packaged libraries, 132 BOM components, seven advisory matches and nine missing license metadata entries. FS-310A17 authorized: pinned patched Bouncy Castle/OTel, compatibility and strict fresh scan gate, artifact-bound license/notice evidence, manual main-only attestation preparation. [Contract](../architecture/46_RELEASE_SECURITY_REMEDIATION.md). CI and owner review pending. V23 unchanged; staging/release NO-GO pending exact final evidence, license/signature/environment review. No deployment authorization, publication, production policy, historical backfill, hold release or provider deletion; PR #87 excluded.


2026-11-16 (Asia/Jakarta): owner approved A17 PR #104 exact head `9b5990d726314229cee7056844baf1fd86d830b6`; squash merged `eb98f1ea2f259e6a345c77117ada9f2237edb1c2`, CI 43839879275 passed 224 tests and zero reported Maven advisories. A18 PR #105 rebased onto main; [release assurance and staging handoff](../architecture/47_RELEASE_ASSURANCE_HANDOFF.md) adds exact-component distribution review, candidate notice bundle and strengthened attestation statement verification. V23 retained; full A18 CI and owner review pending. No license distribution approval, signing dispatch, publication, provisioning, deployment, production policy, historical backfill, legal-hold release or physical provider deletion; PR #87 excluded.


2026-11-16 (Asia/Jakarta): owner approved A18 PR #105 at exact head `9eca4ced4b2e66717ab484bfe58c36c1487845a2`; squash merged `c6713d6088a61ff67fae0dc16c16afd4da971c43`. CI 43846856194 passed 224 Java tests, ten distribution and six statement checks, 133/133 SBOM and 127/127 packaged Maven components, zero reported advisories. A19 authorized for remaining license dispositions and staging acceptance preparation only; [A19 contract](../architecture/48_LICENSE_DISPOSITIONS_STAGING_ACCEPTANCE.md). V23 retained. No distribution approval, signing, publication, provisioning, deployment, production policy, historical backfill, legal-hold release or physical provider deletion. PR #87 excluded. A19 candidate CI and owner review pending.
