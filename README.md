# VyrnForge File Storage

Independent, multi-tenant, multi-provider enterprise file storage service for Asset Management, ITSM and other applications.

**Current checkpoint — 2026-11-17:** Isolated backend runtime, private transfer/worker duties, durable metadata and governance, S3 and native OSS adapters, and separated security/role admission are implemented in reviewed increments through PRs #113 and #112. `main` `a1903778af1e272ae15c025fe28c4cca369c09af` passed [234 Java tests](https://github.com/vyrnforge/file-storage/actions/runs/44010497592), with no failures/errors/skips. **Full clean-code/backend security audit is not accepted**: [#65](https://github.com/vyrnforge/file-storage/issues/65) is reopened with four unchecked acceptance criteria; [audit #108](https://github.com/vyrnforge/file-storage/pull/108) and [review #115](https://github.com/vyrnforge/file-storage/pull/115) are draft. No live IAM/OSS production acceptance, service deployment, public release or signing approval. [Quality control and state matrix](docs/project/15_QUALITY_RECOVERY_CONTROL.md). The entries below are historical milestones, not a live status queue.

## Start here
- [Project constitution](docs/project/00_CONSTITUTION.md)
- [Current state and legacy reuse](docs/project/01_CURRENT_STATE.md)
- [Product scope](docs/project/02_SCOPE.md)
- [Architecture](docs/architecture/01_ARCHITECTURE.md)
- [Storage control plane](docs/architecture/02_STORAGE_CONTROL_PLANE.md)
- [API and integration](docs/architecture/03_INTEGRATION.md)
- [Threat model](docs/security/THREAT_MODEL.md)
- [Delivery roadmap](docs/project/03_ROADMAP.md), [master delivery backlog (#13)](https://github.com/vyrnforge/file-storage/issues/13) and [decision register](docs/project/04_DECISIONS.md)
- [Project context for AI agents](AGENTS.md) and [.gpt instructions](.gpt/GPT_PROJECT_INSTRUCTIONS.txt)

- [Phase 0 architecture review](docs/project/06_PHASE_0_ARCHITECTURE_REVIEW.md)
- [Domain and placement contract](docs/architecture/04_DOMAIN_AND_PLACEMENT_CONTRACT.md)
- [First implementation increment FS-100A](docs/project/07_FIRST_IMPLEMENTATION_INCREMENT.md)
- [Repository security evidence](docs/security/REPOSITORY_SECURITY_EVIDENCE.md)

- [Product outcomes and rules](docs/project/09_PRODUCT_OUTCOMES_AND_RULES.md)
- [FS-100B acceptance and handoff](docs/project/10_FS100B_ACCEPTANCE.md)

- [FS-110 identity and authorization](docs/security/FS110_IDENTITY_AUTHORIZATION.md)

- [FS-110B JWT verifier and deployment gate](docs/security/FS110B_JWT_VERIFIER.md)

- [FS-120A persistence contract](docs/architecture/05_POSTGRESQL_PERSISTENCE.md)

- [FS-120B1 transactional initiation](docs/architecture/06_TRANSACTIONAL_INITIATION.md)

- [FS-120B2A configuration revisions](docs/architecture/07_CONFIGURATION_REVISIONS.md)

- [FS-120B2B durable grant contract](docs/security/FS120B2B_DURABLE_GRANTS.md)

- [FS-120C1 atomic mutation intents](docs/architecture/08_ATOMIC_MUTATION_INTENTS.md)

- [FS-120C2 durable operation and delivery contract](docs/architecture/09_DURABLE_RECOVERY.md)

- [FS-200A provider and transfer contract](docs/architecture/10_PROVIDER_TRANSFER_CONTRACT.md)

- [FS-200B S3 adapter profile](docs/architecture/11_S3_ADAPTER_PROFILE.md)

- [FS-200C native OSS adapter profile](docs/architecture/12_OSS_ADAPTER_PROFILE.md)

- [FS-210A backend registry](docs/architecture/13_BACKEND_REGISTRY.md)

## Ecosystem
[Wyrmgate IAM](https://github.com/wyrmgate/iam) authenticates identities; [Platform Kernel v0.3.2](https://github.com/vyrnforge/platform-kernel/releases/tag/v0.3.2) provides shared context and reliability contracts; [VyrnForge UI](https://github.com/vyrnforge/vyrnforge-ui) provides console foundations; [VyrnRail](https://github.com/vyrnforge/vyrnrail) informs engineering governance. File Storage owns its own domain, database, storage and release.

## Layout
Backend contains the domain, provider SPI, S3/OSS adapters, application, security, persistence and runtime modules. Isolated HTTP/API, private gateway and worker code exists; actual deployed roles, live IAM/OSS integration and production infrastructure remain unverified. Console, SDK and deployment work have separate acceptance gates. The following dated entries record prior milestones. Verify with `make ci` (Java 21, Maven 3.9+, Docker for disposable PostgreSQL and MinIO tests, Kernel from Maven Central). See [FS-100A evidence](docs/project/08_FS100A_EVIDENCE.md). See [CONTRIBUTING.md](CONTRIBUTING.md).

Public repository: never commit secrets, private legacy source, real file content or production configuration. Licensed under [Apache-2.0](LICENSE).

- [FS-210B backend lifecycle](docs/architecture/14_BACKEND_LIFECYCLE.md) — internal validation, drain and guarded retirement in review; concrete provider/authority gates pending.

- [FS-220 policy control plane](docs/architecture/15_POLICY_CONTROL_PLANE.md) — bounded internal policy management in review.

- [FS-300A runtime foundation](docs/architecture/16_RUNTIME_FOUNDATION.md) — isolated executable and proposed v1 API; public exposure blocked.

FS-300A foundation merged (PR #64, 125 verified tests). HTTP boundary refinement in review; public exposure blocked. Engineering quality [#65](https://github.com/vyrnforge/file-storage/issues/65) tracks focused cleanup.

- [FS-300A isolated policy administration](docs/architecture/17_POLICY_ADMIN_HTTP.md) — separate admin authorization, versioned policy routes and real DB contract tests; no public exposure.

- [FS-300A isolated backend administration](docs/architecture/18_BACKEND_ADMIN_HTTP.md) — platform-level registry/lifecycle contracts; no public exposure.

PR #68 accepted/merged (137 tests). Dependency-first delivery: independent lifecycle safety in review, isolated runtime composition next; actual IAM/provider and deployment gates remain open.

- [FS-300A runtime composition](docs/architecture/19_RUNTIME_COMPOSITION.md) — explicit isolated API wiring, schema/authority readiness; no live exposure.

PR #70 accepted/merged a062618 (143 tests); isolated #30 complete, live exposure gates deferred. [FS-300G private transfer gateway](docs/architecture/20_PRIVATE_TRANSFER_GATEWAY.md) — first bounded streaming/authority/provider-neutral foundation in review.

PR #71 accepted/merged 270fd09 (148 tests). [Durable upload transfer authorization](docs/architecture/21_DURABLE_UPLOAD_TRANSFER.md) — V5 digest-only handle and explicit upload grant in review.

PR #72 accepted/merged 4880a42 (152 tests). [Registry-pinned provider transfer](docs/architecture/22_REGISTRY_PROVIDER_TRANSFER.md) — real PostgreSQL/MinIO gateway upload in review; no verified completion or live exposure.

PR #73 accepted/merged 771ed70 (154 tests). [Native OSS and combined gateway](docs/architecture/23_OSS_COMBINED_GATEWAY.md) — isolated integration in review.

PR #74 accepted/merged cbc61ef (155 tests). [Upload integrity verification](docs/architecture/24_UPLOAD_INTEGRITY.md) — #31 V6 durable matching evidence in review; no available state or scan verdict.

PR #75 accepted/merged 15015ec (157 tests). [Durable scan lifecycle](docs/architecture/25_UPLOAD_SCAN_LIFECYCLE.md) — #31 isolated scan work and guarded availability in review; no real scanner/deployment.

PR #76 accepted/merged 05b6b8d (165 tests). [Upload completion commands and worker](docs/architecture/26_COMPLETION_API_WORKER.md) — isolated HTTP/durable verification orchestration in review.

PR #77 accepted/merged 4f65a0d (168 tests). [Terminal upload recovery](docs/architecture/27_TERMINAL_UPLOAD_RECOVERY.md) — terminal replay and logical recovery in review; physical cleanup HOLD.

PR #78 accepted/merged edb185c (173 tests). [Authorized private download](docs/architecture/28_AUTHORIZED_DOWNLOAD.md) — #35 exact clean-version download path in review.

PR #79 accepted/merged b8450b3 (177 tests). [Metadata catalog and usage](docs/architecture/29_METADATA_CATALOG.md) — #35 bounded discovery and logical usage in review.

PR #80 accepted/merged 02814b4 (180 tests). [FS-310A1 reversible logical lifecycle](docs/architecture/30_LOGICAL_FILE_LIFECYCLE.md) — #36 V11 candidate in review; physical purge remains gated.

PR #81 accepted/merged 660f14b (183 tests). [FS-310A2 lifecycle safety](docs/architecture/31_LIFECYCLE_SAFETY.md) — V12 recovery catalog and retention assessment in review; physical purge disabled.

PR #82 accepted/merged a1fd681 (185 tests). [FS-310A3 purge preparation](docs/architecture/32_PURGE_PREPARATION.md) — V13 candidate; executable purge disabled.

PR #83 accepted/merged 65b6ec4 (187 tests). [FS-310A4 reader safety and simulation](docs/architecture/33_READER_SAFETY_SIMULATION.md) — V14 candidate; physical purge disabled.

PR #84 accepted/merged 0d0cc02 (190 tests). [FS-310A5 governed lifecycle](docs/architecture/34_GOVERNED_LIFECYCLE.md) — V15 candidate; physical purge disabled.

PR #85 accepted/merged 8ea998f (193 tests). [FS-310A6 isolated policy and quota](docs/architecture/35_ISOLATED_POLICY_QUOTA.md) — V16 candidate; production policy and physical purge disabled.


2026-11-16 (Asia/Jakarta): PR #86 accepted/squash merged 4af51dd819498d323345475d4a9cc3bf16d28288; exact-head CI 43785045045 passed 197 tests. Owner authorized FS-310A7 isolated transactional quota integration and reconciliation (#13/#16/#33/#35/#36). No production policy, historical backfill, hold release, actual provider deletion or deployment. PR #87 excluded.

- [FS-310A7 isolated transactional quota integration](docs/architecture/36_ISOLATED_QUOTA_INTEGRATION.md) — V17 candidate; implementation CI passed, owner review pending.


FS-310A7 candidate PR #88: [full CI 43801908520](https://github.com/vyrnforge/file-storage/actions/runs/43801908520) passed 199 tests (zero failures/errors/skips) at implementation head `74d22283d2254d482254b19fb68e92e3c8803dcf`; V17 repeatability and two new PostgreSQL regressions. Owner review/merge pending. Final review head verification is recorded on PR #88. Engineering choices: explicit empty isolated tenant, durable tenant fence even for non-enabled initiation (contention/load evidence deferred), transaction-triggered ledger changes and read-only reconciliation. No production activation or deployment.


2026-11-16 (Asia/Jakarta): PR #88 accepted/squash merged 453cc93732bfac19e2b1dbffec3a6737af690a0e; exact-head CI 43802227997 passed 199 tests. Owner authorized FS-310A8 governed quota transitions and reconciliation safety (#13/#16/#33/#35/#36). V18 isolated candidate, independently reviewed quota limit/TTL and immutable dry-run findings. No production policy/limits, hold release, actual historical backfill, provider deletion or deployment. PR #87 excluded.

- [FS-310A8 governed quota transitions and dry-run findings](docs/architecture/37_GOVERNED_QUOTA_TRANSITIONS.md) — V18 candidate; implementation CI passed, owner review pending.


FS-310A8 candidate PR #91: [implementation CI 43803914425](https://github.com/vyrnforge/file-storage/actions/runs/43803914425) BUILD SUCCESS at `fb89dec83148525ace1c6c258e7cd3898b91358e` against main 453cc93: 203 tests, zero failures/errors/skips (58 persistence, 80 runtime, 40 domain/application/SPI/security, 13 S3/MinIO, 12 OSS). V18 migration repeatability and four new PostgreSQL regressions passed. Initial CI 43803640155 was superseded/cancelled; hardened CI 43803742192 failed V18 enablement guard dollar quoting and disposable migration rolled back; corrected full suite passed, no tests removed. Final review-head evidence recorded on PR #91. Owner review/merge pending. New initial and replacement activations require independently reviewed exact limit/TTL; new integration enablement requires reviewed activation. Historical rows retained; no actual historical backfill, production policy, hold release, provider deletion or deployment.


2026-11-16 (Asia/Jakarta): PR #91 accepted/squash merged `11c1a5ead150ec9f77ffd8225f0c070ad154bca3`; exact-head CI 43804275443 passed 203 tests. Owner authorized FS-310A9 (#13/#16/#33/#35/#36), V19 isolated dry-run manifest and restricted execution foundation. [Contract](docs/architecture/38_HISTORICAL_ACCOUNTING_MANIFEST.md). CI and owner review pending. No actual historical backfill, production policy/limits/durations, hold release, provider deletion or deployment. PR #87 excluded.


2026-11-16 (Asia/Jakarta): owner approved PR #92; squash merged `1419360d456079f3ffe3510babdab6eb721302f1`; [exact-head CI 43806355043](https://github.com/vyrnforge/file-storage/actions/runs/43806355043) passed 205 tests. FS-310A10 selected as next isolated increment: restricted lifecycle completion/recovery and atomic Kernel manifest command receipts. [Contract](docs/architecture/39_RESTRICTED_LIFECYCLE_AUDIT.md). CI and owner review pending. Production retention/limits, legal-hold release, actual historical backfill, provider deletion and deployment remain gated; PR #87 excluded.


2026-11-16 (Asia/Jakarta): owner approved PR #94; squash merged `69c77b2803f239e255baef6a1ba652f00616ff40`; [exact-head CI 43821134184](https://github.com/vyrnforge/file-storage/actions/runs/43821134184) passed 207 tests. FS-310A11 authorized isolated execution-boundary hardening: V21 audited principal mode and deferred raw-bypass protection, lock-only identity guards, narrower worker settlement privileges and concurrent completion/recovery regression. [Contract](docs/architecture/40_EXECUTION_BOUNDARY.md). CI and owner review pending. No actual historical backfill, production retention/limits, hold release, provider deletion or deployment; PR #87 excluded.


2026-11-16 (Asia/Jakarta): owner approved PR #96; squash merged `c1f4160c26d7ac0c2f74e5c129c7cb28ea093b10`; [exact-head CI 43823451203](https://github.com/vyrnforge/file-storage/actions/runs/43823451203) passed 209 tests. FS-310A12 authorized: versioned isolated role profiles, effective privilege drift/revocation, bounded quota/manifest workload and logical restore rehearsal, production readiness checklist. [Contract](docs/architecture/41_ROLE_OPERATIONAL_READINESS.md). No new migration or production roles. CI and owner review pending. No historical backfill, production limits/durations, hold release, provider deletion or deployment; PR #87 excluded.


2026-11-16 (Asia/Jakarta): owner approved PR #97; squash merged `fb606a7698df1bbef789efd51732178867ebaa95`; [exact-head CI 43824603192](https://github.com/vyrnforge/file-storage/actions/runs/43824603192) passed 212 tests. FS-310A13 authorized: scoped metadata/transfer/backend administration profiles, V22 lock-only identities, extended privilege drift, isolated credential rotation and quarantined restore/dispatcher restart, staging approval package. [Contract](docs/architecture/42_RUNTIME_ROLE_ACTIVATION.md). CI and owner review pending. No production provisioning, historical backfill, production limits/durations, hold release, provider deletion or deployment; PR #87 excluded.


2026-11-16 (Asia/Jakarta): owner approved PR #98; squash merged `8e48e5ad9c6789b8b7e11ca858b185f56eb92630`; [exact-head CI 43826187384](https://github.com/vyrnforge/file-storage/actions/runs/43826187384) passed 215 tests. FS-310A14 authorized: separated API/internal adapter sources, V3 scoped roles, V23 retention evidence lock identity, fail-closed identity/readiness and HTTP integration, staging go/no-go package. [Contract](docs/architecture/43_SEPARATED_RUNTIME_STAGING.md). CI and owner review pending. Staging deployment not approved; production gates closed; PR #87 excluded.


2026-11-16 (Asia/Jakarta): owner approved PR #100; squash merged `5ea7608abd0961361359035be742d24e93cd0a1e`; [exact-head CI 43829959343](https://github.com/vyrnforge/file-storage/actions/runs/43829959343) passed 216 tests. FS-310A15 authorized: explicit staging runtime with eleven separated identities, exact provider pin/ref admission, bounded verification/scan/recovery and gated dispatcher, packaged S3/OSS adapters, runtime inventory and staging runbook. [Contract](docs/architecture/44_STAGING_OPERATIONS.md). CI and owner review pending. No deployment, production policy, historical backfill, hold release or physical deletion; PR #87 excluded.


2026-11-16 (Asia/Jakarta): owner approved PR #101; squash merged `e8b15d4fe5158daccb2c20acf4707ba3a00b3b63`; [exact-head CI 43833409866](https://github.com/vyrnforge/file-storage/actions/runs/43833409866) passed 217 tests. FS-310A16 authorized: strict staged bootstrap and mounted secret/TLS role sources, Wyrmgate RS256 snapshot readiness, concrete reviewed S3/OSS factories, CycloneDX runtime SBOM and unsigned artifact-bound review evidence, rollback-only role plan and admission evidence form. [Contract](docs/architecture/45_STAGING_BOOTSTRAP.md). CI and owner review pending. V23 retained; actual environment integrations, vulnerability/license disposition, signed provenance and deployment authorization outstanding. No deployment, production policy, historical backfill, hold release or provider deletion; PR #87 excluded.


2026-11-16 (Asia/Jakarta): owner approved A16 PR #103 as engineering/release-evidence baseline only; squash merged `b9420b5d4ef507be32328040b5ea11ad3c330308`; [exact-head CI 43837282298](https://github.com/vyrnforge/file-storage/actions/runs/43837282298) passed 222 tests, 126 packaged libraries, 132 BOM components, seven advisory matches and nine missing license metadata entries. FS-310A17 authorized: pinned patched Bouncy Castle/OTel, compatibility and strict fresh scan gate, artifact-bound license/notice evidence, manual main-only attestation preparation. [Contract](docs/architecture/46_RELEASE_SECURITY_REMEDIATION.md). CI and owner review pending. V23 unchanged; staging/release NO-GO pending exact final evidence, license/signature/environment review. No deployment authorization, publication, production policy, historical backfill, hold release or provider deletion; PR #87 excluded.


2026-11-16 (Asia/Jakarta): owner approved A17 PR #104 exact head `9b5990d726314229cee7056844baf1fd86d830b6`; squash merged `eb98f1ea2f259e6a345c77117ada9f2237edb1c2`, CI 43839879275 passed 224 tests and zero reported Maven advisories. A18 PR #105 rebased onto main; [release assurance and staging handoff](docs/architecture/47_RELEASE_ASSURANCE_HANDOFF.md) adds exact-component distribution review, candidate notice bundle and strengthened attestation statement verification. V23 retained; full A18 CI and owner review pending. No license distribution approval, signing dispatch, publication, provisioning, deployment, production policy, historical backfill, legal-hold release or physical provider deletion; PR #87 excluded.


2026-11-16 (Asia/Jakarta): owner approved A18 PR #105 at exact head `9eca4ced4b2e66717ab484bfe58c36c1487845a2`; squash merged `c6713d6088a61ff67fae0dc16c16afd4da971c43`. CI 43846856194 passed 224 Java tests, ten distribution and six statement checks, 133/133 SBOM and 127/127 packaged Maven components, zero reported advisories. A19 authorized for remaining license dispositions and staging acceptance preparation only; [A19 contract](docs/architecture/48_LICENSE_DISPOSITIONS_STAGING_ACCEPTANCE.md). V23 retained. No distribution approval, signing, publication, provisioning, deployment, production policy, historical backfill, legal-hold release or physical provider deletion. PR #87 excluded. A19 candidate CI and owner review pending.
