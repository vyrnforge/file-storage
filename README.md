# VyrnForge File Storage

Independent, multi-tenant, multi-provider enterprise file storage service for Asset Management, ITSM and other applications.

**Status: Phase 2 — FS-100A and FS-100B merged; FS-110A merged; FS-110B verifier merged; FS-120A merged; FS-120B1 idempotency merged; FS-120B2A configuration CAS merged; FS-120B2B durable grants merged; FS-120C1 atomic intents merged; FS-120C2 recovery merged; FS-200A provider contract merged; DEC-FS012 approved; FS-200B S3 adapter merged (PR #59); FS-200C native OSS adapter merged (PR #60; staging pending); FS-210A registry in review.** No deployed service or released version.

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
Backend contains the domain, provider SPI, S3/OSS adapters, application, security and persistence Maven reactor; console, SDK, contracts, deployment and tests remain reserved areas with README files. FS-110B adds cryptographic access-token verification; no live IAM wiring or runtime API; no deployed service or infrastructure; FS-120A adds service-owned migrations. Verify with `make ci` (Java 21, Maven 3.9+, Docker for disposable PostgreSQL and MinIO tests, Kernel from Maven Central). See [FS-100A evidence](docs/project/08_FS100A_EVIDENCE.md). See [CONTRIBUTING.md](CONTRIBUTING.md).

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

- [FS-310A8 governed quota transitions and dry-run findings](docs/architecture/37_GOVERNED_QUOTA_TRANSITIONS.md) — V18 candidate; CI and owner review pending.
