# FS-120B2B — Durable grant retrieval

Owner authorized [#21](https://github.com/vyrnforge/file-storage/issues/21) after explicitly accepting and merging PR #52 at 32b387cd1b3ee2113832e4eff0ab224da53926d0. Existing FS-110A authorization rules remain authoritative. This is an internal read adapter, not a grant issuance service or IAM deployment.

## Contract
- JdbcGrants implements SecuredFiles.Grants using existing fs.file_grant. Exact tenant, actor and consuming application in parameterized SQL. Revoked, not-yet-valid and expired grants excluded; inclusive not_before, exclusive expires_at. PostgreSQL microsecond timestamps; floor current instant to microseconds for SQL comparison. Security facade rechecks current time after retrieval and action/purpose/ownership/exact-file identity. No implicit owner, tenant admin or cross-app privileges. No wildcard.
- Each check uses a fresh auto-commit read-committed read-only connection and one statement; no cache, no reuse of repeatable-read metadata snapshot. Committed revocation is visible to a subsequent grant query, including during an open metadata transaction; uncommitted revocation is not visible. This is authorization-at-check, not linearizable cancellation: revocation racing an already-authorized in-flight operation may not stop that operation. Later runtime/transfer policy and authoritative revocation requirements must address stronger guarantees; no claim of atomic revocation with a metadata commit.
- Explicit positive query timeout and max grants bound supplied by composition; no production defaults. Query at most max+1 active caller grants, reject overflow rather than authorize from truncated data. Exact filters and existing lookup index. Connection acquisition/pool/network timeouts and connection reset remain deployment gates. Malformed rows, source failure and overflow throw neutral GRANT_SOURCE_UNAVAILABLE; security facade denies. No raw SQL/credentials in errors. Query timeout is not a bound on connection acquisition.
- Existing Kernel RequestContextPipeline still establishes verified identity/binding, membership, entitlement and action before use cases. Initiation/replay rechecks grants through same facade, metadata/session reads authorize authoritative resource. No cached authorization result from idempotency; denied replay does not create another aggregate. Separate read connection means no DB lock is held by grant query across metadata commit; no object I/O.
- Persistence now depends on security module for its existing port/model, not reverse; no new external library, schema migration, public DTO or provider code. Test grants inserted via synthetic SQL only; no issuance or mutation API, no authority assumption about real IAM. Actual grant administration, binding/delegation and issuer configuration remain #17/#19.

## Acceptance
G01 actual secured facade + PostgreSQL initiation/read: exact caller scope, purpose and action, owned-file requires both actor/app, cross-app exact file grants only, metadata vs session separate, cross-tenant denied.
G02 revoked session grant denies replay and read; revoked initiation grant denies before new work; durable request and file counts unchanged.
G03 inclusive start/exclusive expiry at stored precision, future and revoked denied.
G04 uncommitted vs committed revocation in separate grant query while metadata transaction remains open.
G05 source query failure and bounded-result overflow deny; neutral error; recovery without duplicate aggregate.
G06 parameterized caller scope, no cross-tenant/app/actor leakage.
G07 existing regression, V1/V2 service and Kernel V1–V3 migration tests, POM readability/dependency guard. Real PostgreSQL CI required; local acceptance remains outstanding.

## Handoff
QA: verify G01–G07 on compatible head. Security/IAM: authoritative grant issuance, revocation guarantees and actual membership/entitlement sources under #17/#19; no public exposure. Platform: production pool reset, least privilege and acquisition/network timeout policy. Next #22 audit/event intents requires separate owner authorization.

Accepted and merged PR #53 at 137ebeab9a8e83b33219f9d3ae3ccc83f775abd1; [CI 43669360900](https://github.com/vyrnforge/file-storage/actions/runs/43669360900) passed 57 tests (26 PostgreSQL). Issue #21 closed. No live authority or issuance proof.
