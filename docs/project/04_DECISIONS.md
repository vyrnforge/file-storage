# Decision and change register

Owner approval recorded 2026-11-14 (explicit conversation instruction). DEC-FS001–010 and roadmap approved; DEC-FS011 public and Apache-2.0 approved; release version scheme remains open. FS-100A authorized subject to prerequisites. Subsequent owner direction recorded below; no blanket later implementation or merge authorization.

| ID | Decision | Status | Rationale / open issue |
|---|---|---|---|
| DEC-FS001 | Independent file domain, selective modernization | Approved | Preserve proven behavior; owner review of reuse |
| DEC-FS002 | Single product repository, modular monolith | Approved | Avoid premature distributed topology |
| DEC-FS003 | Stable file ID, separate physical locator, pinned sessions | Approved | Safe provider change and migration |
| DEC-FS004 | Console-managed backend/location/policy revisions | Approved | No consumer deployment for new configured backend |
| DEC-FS005 | Compiled S3 and native OSS adapters, capability matrix | Approved | No arbitrary runtime executable plugins |
| DEC-FS006 | Kernel 0.3.2 and IAM boundary; optional JDBC | Approved | Shared contracts, domain owns policy |
| DEC-FS007 | Java 21/Maven/Spring Boot/PostgreSQL/Flyway | Approved | Legacy compatibility and kernel fit; exact framework/database/SDK versions later |
| DEC-FS008 | Client in service repo; optional kernel adapter later | Approved | Avoid cyclic dependency and extra distribution |
| DEC-FS009 | Private-by-default data, public repo only sanitized docs | Approved | Security and least privilege |
| DEC-FS010 | Legacy migration via registered location, no forced byte copy | Approved | Preserve IDs; confirm actual data/compatibility |
| DEC-FS011 | Apache-2.0, public repository | Approved; version scheme open | Owner confirmed public and Apache-2.0; release numbering later |

Decision approval must record date, approver, rationale, alternatives, consequences and affected contracts. Never change status from proposal based on assistant inference.

## Change log
2026-11-14: new repository bootstrap; initial Phase 0 documents and reserved directories. No service code, dependency lock, runtime migrations or deployment. No architecture decision accepted by this change alone.

2026-11-14: owner reports GitHub settings applied; active ruleset and CI merge gate verified. [Phase 0 decision package](06_PHASE_0_ARCHITECTURE_REVIEW.md) refines recommendations and [FS-100A](07_FIRST_IMPLEMENTATION_INCREMENT.md) is ready for review. No ADR approved by inference. See [security evidence](../security/REPOSITORY_SECURITY_EVIDENCE.md).

2026-11-14: Owner explicitly approved DEC-FS001–010, roadmap, recommended stack, public visibility and Apache-2.0, confirmed remaining security settings, and authorized FS-100A conditional on prerequisites. Exact framework/database/SDK versions, release numbering, legacy migration and later increments remain open. Approval rationale/alternatives in [review package](06_PHASE_0_ARCHITECTURE_REVIEW.md); no additional instructions.

2026-11-14: Owner directed continuation based on requirements using Product & Business Analysis after FS-100A merge. Interpreted as authorization of next bounded FS-100B application foundation per approved roadmap, not new policy exceptions, later phases or deployment. See [outcome/rules](09_PRODUCT_OUTCOMES_AND_RULES.md) and [acceptance](10_FS100B_ACCEPTANCE.md). Unresolved product policy remains owner-controlled.

2026-11-14: Owner completed FS-100B review and directed proceeding to next increment. PR #6 merge verified. Interpreted as FS-110 authorization within approved DEC-FS006/009, not new policy exceptions or later work. FS-110A implements deny-by-default exact grants and trusted verifier/binding ports; live issuer/audience, machine identity, grant administration and production integration remain unresolved (FS-110B). See [security contract](../security/FS110_IDENTITY_AUTHORIZATION.md).


## Approved dependency maintenance amendment to DEC-FS006
2026-11-15: Owner authorized completion and merge of the kernel remediation adoption. Upgrade from io.vyrnforge.platform:platform-kernel:0.3.0 to io.github.vyrnforge:platform-kernel:0.3.2 across all current modules including FS-110A security. Rationale: use verified signed public artifact and audit fixes; alternatives retain unpatched artifact or source candidate. Preserve Java imports, v0.2 wire contracts, opaque IDs (512-character released FileReference limit), domain ownership and existing security rules. Remove old manual bootstrap; use anonymous Maven Central and strict checksums. PR #8 initial FS-100B CI passed 18 tests; fresh combined CI required. No authorization of later product increments or deployment.

2026-11-15: Owner directed next after PR #7 merged. Implement FS-110B verifier slice only. Implementation choice: Nimbus JOSE+JWT 10.10 (Apache-2.0), explicit trusted RSA key snapshots, exact single-client access-token profile matching pinned Wyrmgate issuer. Dedicated File Storage client registration is proposed deployment direction, not applied or approved IAM configuration. No new machine/delegation policy, cross-repo changes, deployment or FS-120 authorization.
2026-11-15: Resolved PR #9 conflicts against merged PR #8; retain approved Kernel 0.3.2 Central coordinates and all FS-110B changes. No new scope, merge or deployment authorization.

2026-11-15: Owner directed next after POM maintenance PR #10 merged. Interpret as FS-120A only. Use PostgreSQL 17 for disposable CI, Flyway 13.12.1, pgjdbc 42.7.14 and Testcontainers 1.21.4 aligned with Kernel v0.3.2; no production major/version selection or deployment. Service migrations in separate classpath location and schema fs, avoid Kernel migration version collision. JDBC adapter persists initiation/read aggregate only, explicit transaction and tenant-scoped lookups; use Kernel reliability transaction in FS-120B/C rather than duplicating audit/outbox/idempotency lease logic. Technical identifier lengths and timestamp precision documented in persistence contract.

2026-11-15: Owner confirmed PR #11 merged after recommendation to proceed FS-120B. Interpreted as next bounded FS-120B1 only, not blanket scope or merge authorization. Adopt published platform-kernel-jdbc 0.3.2, separate fs_kernel schema/history, explicit lease TTL, versioned actor/consumer-bound hashed key and fingerprint; stored result only session ID, replay reauthorizes and reloads. Existing initiation_request permanent uniqueness remains (even after lease expiry); retention policy deferred. B2 config CAS/grants and C audit/outbox remain separate. No production migration or external changes.

## Delivery planning record — 2026-11-15
Owner confirmed PR #12 merged and requested the Product & Business Analysis / Technical Delivery Lead work breakdown before advancing. [Master delivery tracker #13](https://github.com/vyrnforge/file-storage/issues/13) and work issues #14–#50 map the approved roadmap to bounded acceptance and evidence packages. FS-120B2A (#20) is the recommended next candidate; no additional runtime increment, product policy, production target, named staffing assignment, release date or deployment approved in this planning step. Existing decisions and outstanding gates remain in force.

2026-11-15: Owner instructed next implementation with Backend & Domain Engineering, authorizing FS-120B2A #20 only. Reuse Kernel 0.3.2 JdbcReliabilityTransaction; read-committed control-plane CAS locks plus existing repeatable-read initiation snapshots. V2 seals existing policies; new publication advances expected+1, rollback is a new revision, backend activation drains previous revision, initial activation expected=0 only with no history. No direct reactivation/retirement or production admin authority. No new dependency or policy exception.

2026-11-15: owner reviewed #20, explicitly instructed merge and proceed. PR #52 squash merged at 32b387cd1b3ee2113832e4eff0ab224da53926d0; #20 closed with hosted CI 43668967693. #21 authorized only: use existing fs.file_grant and SecuredFiles.Grants, no schema change; persistence depends on security port (one-way), fresh committed read per check, configurable query timeout/result bound, fail closed on overflow/source failure. No in-flight revocation linearization, issuance or admin API; #17/#19 and #22 remain separate.

2026-11-15: owner approved proceeding and moving to next issue; interpreted as merge of reviewed PR #53 and authorization of #22 only. Squash merge 137ebeab9a8e83b33219f9d3ae3ccc83f775abd1. FS-120C1 uses Kernel v0.3.2 AuditEvent/EventEnvelope and existing JDBC writers; internal event v1 for initiation, backend activation, policy publication. Minimal identifiers/revisions, canonical actor without issuer/subject/display name; explicit trusted actor/correlation for admin commands. No read/denial audit policy approval, event publication, grant issuance, production migration or #23 authorization.

2026-11-15: owner reviewed and explicitly directed merge PR #54 and next issue. Squash merge 271403d9eb968a2224f7cab963da486b3b0eca89; #22 closed, #23 authorized. Adopt Kernel JdbcOutboxPublisher 0.3.2 with bounded ticks and neutral transport error, service V3 typed operation ledger, immutable request identity via unique tenant/kind/key and target match, persisted attempt budget, claim token/expiry CAS and bounded dead inspection. No auto redrive, live broker, provider effects, scheduling, global capacity/defaults or public worker API; future handlers must authorize/reconcile and fence metadata completion. No #24 authorization.

2026-11-15: owner explicitly instructed merge PR #55 and proceed next. Squash merge b5e8159843fb41121eeabe309497899c3bcd7257; #23 accepted. #24 authorized for contract/test work only. Reusable test-only SPI classifier and stricter metadata/byte semantics specified; private service-controlled streaming/tickets proposed (DEC-FS012, pending). No direct-provider consumer grant exception, actual SDK version/profile, production limits or later implementation approved. See provider transfer contract.
