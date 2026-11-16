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
