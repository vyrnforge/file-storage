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
