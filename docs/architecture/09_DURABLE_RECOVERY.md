# FS-120C2 — Durable operations and outbox recovery

Owner authorized [#23](https://github.com/vyrnforge/file-storage/issues/23) after PR #54 merged at 271403d9eb968a2224f7cab963da486b3b0eca89. Existing service mutations produce Kernel audit/outbox intents. This slice implements an internal worker foundation, not a deployed worker or actual object effects.

## Event delivery
- OutboxDelivery delegates claim, retry, attempts, expired claim recovery and finalization to Kernel 0.3.2 JdbcOutboxPublisher; uses fs_kernel on fresh read-committed connections. Explicit injected EventTransport, Clock, positive lease, max attempts, nonnegative delay and bounded tick size. Transport called after claim transaction commits. Neutral DeliveryFailed exception replaces transport exception messages/class names; infrastructure failures neutral. No scheduler/broker choice, no actual production delivery or timeout enforcement. Transport must have bounded external calls and acknowledge durable receipt; synchronized clocks and lease sizing are deployment prerequisites.
- Stable eventId across retry; at-least-once attempts, not exactly-once or guaranteed eventual delivery with finite retries. Kernel checks token and lease before finalization; an expired worker can still have sent an event. Consumer dedup must be transactional with its effect. Test receiver persists eventId with unique constraint, not a real Asset/ITSM consumer.
- Bounded dead-event inspection by exact tenant (null explicitly global only) returns minimal ID/type/attempt/error, no payload. Dead rows require operator reconciliation; no automatic redrive. Event ordering not guaranteed. Production routing, authorization, retention, redrive, monitoring and broker/consumer contracts remain gates.

## Service operation ledger
- V3 fs.storage_operation separate from Kernel outbox. Tenant/file/version/location composite FK pins an existing version; unique tenant/kind/opaque operation key. Request match includes target and maxAttempts; replay returns original operation ID, retains original correlation. No raw locator/key/credential/provider payload. Kinds are reserved for future handlers (VERIFY_UPLOAD, CLEANUP_UPLOAD, PURGE_VERSION, MIGRATE_VERSION); migration target and operation-specific state/authorization must be added before those handlers execute. No automatic scheduling from initiation. Job enqueue uses caller-owned transaction; failure requires rollback. No public API or worker authorization boundary.
- claim(kind, limit) uses a single atomic read-committed UPDATE with FOR UPDATE SKIP LOCKED candidates, bounded per tick, increments persisted attempt count and sets UUIDv7 claim token/deadline. Expired claims reclaim; exhausted expired claims go DEAD. Tokens scoped to operation ID + tenant. stillOwned is advisory; finish CAS requires current token and unexpired lease; no stale update after another worker claims. Transient failure delays retry until available_at, permanent/exhausted becomes DEAD; success SUCCEEDED. Bounded tenant-specific dead inspection; no automatic destructive redrive.
- finishOnConnection requires caller-owned transaction; future handlers must roll back metadata changes if it returns false, and use exact current resource/state/authorization checks. DB fencing is not fencing of external provider effects. Handler must perform idempotent or reconciled effects, re-read authoritative metadata/placement and validate result before committing domain mutation + audit/events + fenced finish. This slice does not implement those handlers or claim their end-to-end recovery. Service-wide worker claims by reviewed kind; deployment authorization/roles still required. No generic executable jobs, arbitrary payload or runtime plugins.
- Injected clocks, microsecond DB time, bounded batch (not a hard total queue capacity), retry/lease/attempt config with no production default. Queue capacity, retention/cleanup, indexes at scale, scheduler concurrency, network limits, alerting and operator redrive policy remain #16/#44 and real handler work. Terminal records retained for reconciliation; no deletion on success. V1/V2 and Kernel V1–V3 unchanged.

## Acceptance
W01 upgrade V1->V2->V3 and repeat migrations in real PostgreSQL.
W02 enqueue rollback, durable replay, changed request conflict, tenant FK rejection, bounded kind-specific claims.
W03 expired claim reclaim, stale completion denied, concurrent claim one winner.
W04 retry delay, exhausted crash and permanent failure to DEAD, tenant-scoped bounded dead inspection, caller-transaction finish rollback.
W05 Kernel outbox bounded retries/dead, minimal error and dead inspection.
W06 simulated crash after durable receiver acceptance, retry same event ID and one durable receiver record; stale Kernel claim cannot finalize newer claim.
W07 prior tests, POM formatting and dependency checks. No new external dependency.

## Handoff
#24 provider and privacy contract requires owner/security decisions in #16. #25/#26 adapters and #300/#310 actual workflows must use these foundations with per-kind effect reconciliation and tests. #42/#44 actual scheduler/broker/roles/clock/monitoring/recovery. Local full verification remains outstanding. No production migration, deployment or later authorization.

Accepted PR #55 squash merged at b5e8159843fb41121eeabe309497899c3bcd7257 after owner review; [CI 43670297365](https://github.com/vyrnforge/file-storage/actions/runs/43670297365) passed 69 tests (38 PostgreSQL). Issue #23 closed. Provider contract #24 next; actual worker handlers remain later.
