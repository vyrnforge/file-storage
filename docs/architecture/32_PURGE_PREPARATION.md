# FS-310A3 — Durable purge preparation

Status: owner-authorized isolated candidate; PR #82 accepted/merged a1fd681, exact CI 43712395352 passed 185 tests. #36. No production migration, retention policy or physical deletion.

## Internal review command

SecuredFiles.preparePurge is deliberately internal (no HTTP route, scheduler or admin UI). Requires trusted identity, tenant membership, entitlement, explicit PREPARE_PURGE action and current external owned/exact resource grant intersected with active local grant, locked in transaction. Input is logical file, exact version, odd deletion generation and expected policy revision. No caller placement or evidence. Only clean AVAILABLE/COMPLETED content and current SOFT_DELETED generation qualify. Current evidence is locked, must match policy revision and yield REVIEW_REQUIRED (fresh, explicit CLEAR hold, elapsed resolved retention). This is a request for review, not approval or execution. Policy evidence is not yet an authoritative production policy decision.

## Durable identity and fencing

V13 immutable fs.purge_preparation has UUIDv7 operation ID, unique tenant/file/deletion generation, exact version/location/backend/configuration revision, SHA-256 of object key (no raw key), measured content digest and size, policy/evidence timestamps, requester and original correlation. It records prior claimed downloads as a historical signal; reader_safety is always UNVERIFIED, even if no claim exists. No inference that expiration/claim absence proves quiescence. No provider calls. File row exclusive lock serializes with restore/delete and download issue/claim; exact target and current evidence/grant rows are checked in the same transaction. Kernel audit/outbox atomic with insertion; event contains logical IDs/revisions/disposition only, not pin or evidence details. Concurrent same requester retries return one operation ID; replay reauthorizes and rechecks exact snapshot. Changed evidence/target/requester, stale generation or expired/held evidence blocks replay. One immutable request per generation; supersession/reapproval after evidence changes needs a separately approved design. Restore remains allowed and invalidates current-generation eligibility; historical record persists. New deletion needs fresh evidence and new operation identity.

## Hard execution gate

V13 disallows PURGE_VERSION in fs.storage_operation (existing reserved rows would block migration, never silently discard). JdbcOperations.enqueue also denies this kind; generic claim remains empty. V11 rejects PURGE_PENDING/PURGED. Preparation is not a worker lease or execution token and cannot be updated/deleted. No provider deletion path, no task scheduling, no new service runtime role. Future approved execution needs authoritative retention/hold policy writer and current-policy recheck; reader leases/drain acknowledgement with bounded network I/O and explicit quiescence; quota accounting; version/locator verification and fenced operation claim; idempotent provider absence verification and reconciliation; audit and outbox finalization. Database lease alone does not fence external effects. No production policy or defaults are invented.

## Verification

PostgreSQL migration repeatability; explicit action and external/local grant intersection; missing, unknown, held, unresolved, active, expired and changed evidence; wrong version/tenant/requester; exact identity, immutable records, concurrent replay, audit/outbox rollback, restore race, re-delete, generic enqueue and database purge gates. Existing upload, scan, download and recovery tests retained. Exact CI evidence recorded on PR after execution.
