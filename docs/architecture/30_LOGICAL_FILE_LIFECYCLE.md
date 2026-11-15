# FS-310A1 — Reversible logical file lifecycle

Status: owner-authorized isolated implementation, review/CI pending. #36; #35 deleted-file denial. Baseline #80 merged 02814b4, 180 verified tests. No production application.

## Contract

POST /v1/files/{fileId}/delete?expectedRevision=0 and POST /v1/files/{fileId}/restore?expectedRevision=1. Both use current trusted tenant binding, entitlement, explicit DELETE_FILE or RESTORE_FILE and owned/exact resource grant scope intersected with active local grants. The ordinary metadata and catalog responses include lifecycleRevision; lifecycle commands return only stable FileReference, state and revision. Consumers preserve the revision from deletion for restore; no trash browsing API in this slice.

V11 permits ACTIVE (even revision, null deleted_at) and SOFT_DELETED (odd revision, non-null deleted_at). Only clean AVAILABLE/COMPLETED files are eligible. A file row lock orders transitions against download issue/claim; local grant row lock fences revocation until commit. External authority is checked at request time, not continuously. State CAS and Kernel audit/outbox commit atomically. An immediate retry of the same expected revision/target returns the current result without another mutation; stale commands after a later transition conflict. No provider calls, object changes, quota changes or retention scheduling.

## Read isolation

Deleted files are excluded from metadata, session, catalog and logical usage, and download issuance and claim. Initiation replay denies a deleted aggregate; permanent uniqueness prevents duplicate creation. Existing download handles are backfilled generation zero; issuance pins current lifecycleRevision and claim requires the same generation. Pre-deletion handles never become usable after restore. Already claimed streams may continue; no claim of revocation of delivered bytes. File ID, version, location, provider bytes and scan evidence remain unchanged. Ordinary logical usage excludes deleted files; this is not quota release or physical usage.

## Verification and gates

V11 repeatability, PostgreSQL lifecycle and HTTP contract tests, grant separation, stale handles, concurrent retry, audit/outbox rollback, no physical deletion. Exact CI evidence recorded on PR after execution. Retention/holds, trash quota, deleted-file discovery and recovery UX, non-clean/in-flight lifecycle, verified purge and cleanup, live IAM/provider/scanner and production migration/deployment remain separately gated.
