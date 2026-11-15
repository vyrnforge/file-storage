# FS-310A2 — Lifecycle safety and recovery

Status: owner-authorized isolated candidate. Baseline PR #81 merged 660f14b, exact-head CI 43710854973 passed 183 tests. #36 and #35. No production migration/deployment.

## Recovery catalog

GET /v1/deleted-files?limit=50&afterFileId=... requires LIST_DELETED_METADATA, independent from LIST_METADATA, DELETE_FILE, RESTORE_FILE and DOWNLOAD_TRANSFER. Current trusted external scopes intersect local active grants (owned or exact file, actor/application/tenant/purpose) before keyset pagination. Same bounded 1..100 limit and C-order logical ID as the ordinary catalog, no cross-page snapshot promise. Response items contain safe metadata (including lifecycleRevision) and deletedAt; no session, physical locator, hold, retention or provider details. Restored records disappear; ordinary catalog and logical usage still exclude deleted records. Discovery alone never restores or downloads.

## Retention safety

V12 adds optional internal file_retention_evidence keyed by tenant, logical ID and odd deletion generation. Facts have policy revision, explicit UNKNOWN/HELD/CLEAR hold, optional retain-until, observation and validity window. No default retention period, no automatically clear hold, no production policy writer or consumer/admin endpoint. Missing or stale evidence, unknown/held hold, unresolved retention and active retention block review. Even elapsed retention plus fresh explicit CLEAR yields REVIEW_REQUIRED, not authorization. A restore/re-delete invalidates old evidence for the current generation. Assessment is read-only, not a transactional purge permit or proof of current policy authority; a later approved writer and current-policy/hold recheck, quota, reader quiescence and durable purge transaction are required. V11 still rejects PURGE_PENDING/PURGED. No provider delete, scheduling or cleanup. Synthetic evidence is seeded solely in tests.

## Verification scope

PostgreSQL and HTTP recovery catalog, overlap and scope intersection, revocation, pagination, redaction, restored disappearance; retention missing/unknown/held/unresolved/active/expired/stale/new generation and DB purge denial. Extend real MinIO and native OSS loopback combined HTTP upload/download tests with deletion/restore: denied old handle, no provider opening, fresh handle byte equality. Actual cloud staging and live authority remain gated. Exact CI recorded on PR after execution.
