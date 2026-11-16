# Retention, legal hold and quota — owner decision review

Status: proposed, not approved. #16/#33/#36. No production values or authorization inferred from isolated tests.

| Decision | Proposed options / recommendation | Owner disposition |
|---|---|---|
| Retention duration | Explicit tenant default; optionally longer application/purpose override. Select duration and minimum/maximum from legal/business requirements; no automatic default. | Open |
| Effective policy | Versioned approved revision, effective time, pin at deletion; recommend later changes only extend existing retention unless separately reviewed exception. | Open |
| Policy precedence | Tenant mandatory floor, application/purpose stricter override; unresolved scope denies purge. | Open |
| Hold authority | Named tenant legal/compliance role, exact file/version or scope; independent release reviewer, durable evidence, no expiry-based release. Define case ID, escalation and jurisdiction rules. | Open |
| Trash quota | Recommend charge until verified physical purge; alternative release on logical delete requires separate physical/trash budget and restore reservation. | Open |
| Quota concurrency | Reserve at initiation, convert on verified completion, release once on terminal outcome under approved accounting; purge release only after provider absence and fenced metadata transaction. Define limits and restore admission. | Open |
| Reader reconciliation | Independent reviewer and trusted evidence of exact remote stream termination; no TTL-only clearance; define approved telemetry, identity, evidence storage and verification. | Open |
| Purge authority | Separate approved execution role, policy/hold recheck and reader drain; exact version/placement; absent object verification, retries, audit and quota finalization. | Open |

Boundary examples: retention deadline equal to now does not itself permit purge; a hold spanning restore/re-delete still blocks; a changed policy invalidates old preparation; a crashed reader with expired lease remains unresolved; logical deletion does not prove physical bytes are freed. Tenant A cannot authorize tenant B. No provider deletion, production policy activation or deployment authorized by this review.

A6 candidate: isolated tenant-only policy activation, explicit quota limit, nondecreasing retention and CHARGE_UNTIL_VERIFIED_PURGE ledger primitives are engineering fixtures only. No production decision changed. See [V16 contract](../architecture/35_ISOLATED_POLICY_QUOTA.md). Production scope overrides, effective-date/deletion pin rules, hold release, limits, accounting integration/backfill, reader proof and purge remain open.


2026-11-16 (Asia/Jakarta): PR #86 accepted/squash merged 4af51dd819498d323345475d4a9cc3bf16d28288; exact-head CI 43785045045 passed 197 tests. Owner authorized FS-310A7 isolated transactional quota integration and reconciliation (#13/#16/#33/#35/#36). No production policy, historical backfill, hold release, actual provider deletion or deployment. PR #87 excluded.
