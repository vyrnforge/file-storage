# FS-120B2A — Transactional configuration revisions

Owner authorized [#20](https://github.com/vyrnforge/file-storage/issues/20) after PR #51 merged. Baseline d48e250c3bd289ff463a9fac44136979bc5c502c; DEC-FS004, domain placement contract and existing FS-120B1. No public admin API or provider network calls.

## Internal commands
- JdbcConfiguration is a trusted persistence boundary, not an authorization boundary. The future control plane must authorize the admin and exact eligible locations, supply real validator evidence, and use required audit/event intents (#22) before exposure. No grant administration.
- ActivateBackend(backend, expected, candidate): lock backend identity row, read current active status, require exact expected active revision (0 only for first activation with no published history), candidate strictly newer than all activated history and DRAFT with validation of that exact revision. Drain previous active revision, activate candidate in one Kernel JdbcReliabilityTransaction. No reactivation of drained/retired history; no retirement or physical repoint. Credential rotation keeps old session pins; actual credential resolution later.
- PublishPolicy(scope, expected, rules): exact tenant/application/purpose; nonempty distinct locations, nonnegative priorities; new revision expected+1 (0 for initial), immutable assembled rule payload. Lock referenced backend identities in shared mode, lock current policy pointer when present, insert revision/rules, seal, CAS pointer, reuse actual StorageModel.select to reject ambiguous/no eligible/active winner. No silent fallback. Competing initial publications conflict via unique revision key; existing pointer serializes competing updates. Rollback to old routing is a new revision with historical content, not a pointer rewind. No auto-retry: stale expected returns CONFLICT, invalid candidate INVALID, no placement NO_PLACEMENT, unknown backend NOT_FOUND; neutral DB errors.
- Kernel JDBC 0.3.2 execute provides one transaction on same database, read committed for control commands; existing initiation uses repeatable-read configuration snapshot and durable idempotency unchanged. A request already reading the previous snapshot can commit a pinned historical revision; new snapshots see committed configuration. No exactly-once claim for admin mutations; admin idempotency/audit later.

## V2 migration
V1 unchanged. Seal all existing policy revisions without changing rules/pointers. New revisions assemble then seal; DB triggers reject edits/deletes, rule insertion into sealed history, pointer rewind/unsealed target, backend revision payload edits/deletes, stale validation and invalid state transitions. Backend identity lock serializes revision writes with activation. Existing physical identity guards and session foreign keys remain. Retirement blocked until reference/job guards are implemented. DB owner can bypass triggers/privileges; production roles and validator authorization still release gates. Test fixture raw SQL can assemble a deliberately invalid sealed policy to prove fail-closed readers; publication command always validates. No production DB applied.

## Acceptance
C01 V1 upgrade preserves existing config and seals history; fresh migrations and repeat migrate work.
C02 exact validation required; valid activation drains previous, keeps old session pins; new session uses new config.
C03 competing backend activation against same expected revision yields one winner and one conflict.
C04 competing policy publications (existing and initial) yield one winner, no orphan revision.
C05 invalid/ambiguous/unauthorized winner fails with rollback; historical rules and pointer rewinds rejected; rollback via new revision.
C06 actual application policy switch and repeatable-read snapshot, durable replay and all previous regression tests unchanged. Real PostgreSQL evidence required.

## Handoff
#21 durable grants, #22 atomic audit/event intents, #23 recovery; #27/#28 real registry, validation, retirement, #29 control plane and #30 authorized API. No public route or production use until those and live IAM gates. Local Maven/Docker verification remains outstanding. No new dependency or POM changes.

## Accepted implementation evidence
Owner reviewed and authorized squash merge of PR #52 at 32b387cd1b3ee2113832e4eff0ab224da53926d0. [CI 43668967693](https://github.com/vyrnforge/file-storage/actions/runs/43668967693) passed 51 tests, 20 real PostgreSQL 17.12; V1-to-V2 upgrade, concurrency, rollback, immutability and pinned sessions. Initial synthetic trigger delimiter error corrected. Issue #20 accepted/closed; no production migration.

FS-120C1 adds mandatory trusted actor and correlation arguments to internal configuration commands, with required audit/event writes in the same transaction. No admin authorization boundary or public API. See [atomic intents](08_ATOMIC_MUTATION_INTENTS.md).
