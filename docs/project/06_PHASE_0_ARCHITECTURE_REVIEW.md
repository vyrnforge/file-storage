# Phase 0 architecture decision package

Status: APPROVED direction — owner explicit approval 2026-11-14. Based on main d3e8de48c39e48ab9469ad7f7553084121ab57a7, project constitution, scope, current state and Kernel v0.3.0. FS-100A separately authorized; no later scope authorized.

## Decision slate

| ID | Recommended choice | Alternatives / consequences |
|---|---|---|
| DEC-FS001 | Independent service; selective foundation rebuild | Patch legacy vs new model: retain behavioral tests and OSS semantics, replace singleton storage coupling. |
| DEC-FS002 | Product monorepo; modular monolith | Separate microservices add coordination. API and worker can scale separately from same product release. |
| DEC-FS003 | Stable opaque file ID, immutable versions, physical locators | No bucket/key in public reference; historical placement remains resolvable. |
| DEC-FS004 | Versioned backend/location/policy registry; deterministic placement | Static config cannot safely support dynamic routing. Activate validated revisions; pin sessions; drain before retire. |
| DEC-FS005 | Compiled S3 and native OSS adapters | No runtime code upload; S3-compatible targets supported only after contract tests. |
| DEC-FS006 | Kernel 0.3.0, wire v0.2, trusted IAM, optional JDBC | No duplicate context contracts. Domain permissions remain local. No kernel changes. |
| DEC-FS007 | Java 21, Maven 3.9+, Spring Boot service and PostgreSQL/Flyway target | Preserve legacy ecosystem fit; pure Java domain. Exact supported Spring/driver/SDK versions and database major selected at first relevant dependency PR with compatibility/security evidence. |
| DEC-FS008 | Client in service repo; optional kernel integration later | Keep core independent; avoid speculative common client abstraction. |
| DEC-FS009 | Private objects by default, scoped grants, secrets external | Public source does not mean public bytes. Console endpoints need SSRF controls. |
| DEC-FS010 | Legacy import at existing location, preserve IDs | No forced byte copy; migration conditional on inventory and actual compatibility. |
| DEC-FS011 | Public repository; Apache-2.0 approved | License/visibility approved; release versioning remains open. |

## Proposed minimum release scope
Two installed providers (S3 plus native OSS), dynamic backend/location/policy management, stable FileReference, secure upload/download and metadata, batch/session/quotas, scan/quarantine, lifecycle/delete/restore, durable audit/events/idempotency, bounded reconciliation, admin console, SDK and operational acceptance. Legacy migration conditional on actual data. Provider replication, advanced search, arbitrary plugins, filesystem protocols and generic workflow excluded.

## Decisions requiring more detail before affected implementation
- Identity: service audience, membership and entitlement authority, machine identity and delegation contract; no assumed IAM claims. Default deny if unresolved. File service grants bind tenant, consumer and action/resource. Consumer checks business resource; File Storage checks file access independently.
- Configuration: logical service ID `file-storage`; location IDs immutable; revision compare-and-set and durable activation record. New placement resolves active eligible location; existing reads use persisted locator. No silent failover to different region/provider; explicit eligible policy only. Secret manager selected by deployment; never save raw secrets in backend record.
- Lifecycle: immutable version IDs; staged upload, server-verified completion, scan gate, available, soft-delete, purge pending, purged; reserve quota atomically; no automatic purge from consumer detach. Download grant is short-lived and not a durable reference.
- Production: target cloud, S3-compatible test matrix, database major, scanner, secret manager, quota/retention/URL defaults, RPO/RTO/SLO and expected file sizes remain open. These block their related work or release, not an isolated domain-model increment.
- Compatibility: legacy source is private; full build and data inventory not verified. Retain existing IDs and characterize API behavior before compatibility promises; no legacy import to public repo.
- Governance: solo reviewer mode is temporary; owner confirms public/private and license, independent reviewer later. Security verification evidence in docs/security/REPOSITORY_SECURITY_EVIDENCE.md.

## Approval gate
Owner approved DEC-FS001–010, roadmap, public/Apache-2.0 and FS-100A on 2026-11-14. Release numbering remains open. Record decision IDs, approver/date and exceptions in the register. No runtime implementation before that. Later increments need their own acceptance criteria and evidence. Approval of a documentation PR alone is not architecture approval.
