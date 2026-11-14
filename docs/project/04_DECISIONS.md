# Decision and change register

All entries below are PROPOSED unless explicitly marked confirmed. User requested independent service, no unnecessary rebuild, and review before implementation; these constraints are confirmed. Creating a new repository confirms repository identity, not stack or release approval.

| ID | Decision | Status | Rationale / open issue |
|---|---|---|---|
| DEC-FS001 | Independent file domain, selective modernization | Proposed | Preserve proven behavior; owner review of reuse |
| DEC-FS002 | Single product repository, modular monolith | Proposed | Avoid premature distributed topology |
| DEC-FS003 | Stable file ID, separate physical locator, pinned sessions | Proposed | Safe provider change and migration |
| DEC-FS004 | Console-managed backend/location/policy revisions | Proposed | No consumer deployment for new configured backend |
| DEC-FS005 | Compiled S3 and native OSS adapters, capability matrix | Proposed | No arbitrary runtime executable plugins |
| DEC-FS006 | Kernel 0.3.0 and IAM boundary; optional JDBC | Proposed | Shared contracts, domain owns policy |
| DEC-FS007 | Java 21/Spring Boot/PostgreSQL candidate | Proposed | Legacy compatibility and kernel fit; confirm versions |
| DEC-FS008 | Client in service repo; optional kernel adapter later | Proposed | Avoid cyclic dependency and extra distribution |
| DEC-FS009 | Private-by-default data, public repo only sanitized docs | Proposed | Security and least privilege |
| DEC-FS010 | Legacy migration via registered location, no forced byte copy | Proposed | Preserve IDs; confirm actual data/compatibility |
| DEC-FS011 | License, visibility, release/version scheme | Open | Owner decision required; repository currently public |

Decision approval must record date, approver, rationale, alternatives, consequences and affected contracts. Never change status from proposal based on assistant inference.

## Change log
2026-11-14: new repository bootstrap; initial Phase 0 documents and reserved directories. No service code, dependency lock, runtime migrations or deployment. No architecture decision accepted by this change alone.
