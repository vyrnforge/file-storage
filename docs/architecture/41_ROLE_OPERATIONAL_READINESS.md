# FS-310A12 — Role profiles and operational rehearsal

Status: candidate; CI and owner review pending. A11 PR #96 accepted, merge c1f4160c26d7ac0c2f74e5c129c7cb28ea093b10, exact-head CI 43823451203 passed 209 tests. Refs #13/#16/#33/#35/#36.

## Version 1 isolated role profiles

The source of truth for the **rehearsal grant matrix** is `IsolatedRoleProfiles.java` in runtime test sources. It is not a runtime component, migration, deployment script or production role provisioner. No new schema version. Separate NOLOGIN groups and disposable LOGIN principals; no shared login across duties. Explicit owner-only provisioning and group membership. Profile drift checks compare effective table and column privileges (including inherited and PUBLIC privileges), schema usage/create, all non-trigger service functions and role flags/membership. Any additional parent edge (including non-inheriting SET ROLE membership) is rejected. Table grant options are checked; column/function/schema grant options, database-wide privileges, objects outside fs/fs_kernel, role settings and default ACL review remain required for production. The pre-existing V4 PUBLIC invoker retirement helper is explicitly tracked; it has no elevation and still needs schema/table access. Trigger functions are not ordinary SQL-callable. No automatic privilege remediation.

| Profile | Allowed | Excluded / remaining trust |
|---|---|---|
| INITIATOR | Existing initiation/read grant path, metadata inserts, quota reservation/fence, Kernel idempotency and audit/outbox intent | Trusted writer; no policy activation, purge or dispatch |
| LIFECYCLE | Scan operation/evidence, session/version state, recovery, read locks, Kernel intents | No direct quota settlement/fence writes; trusted multi-tenant worker, not tenant RLS |
| MANIFEST | Audited wrapper/scoped read, Kernel intents; individual login mapped to tenant/actor/CAPTURE or REVIEW and AUDITED mode | No raw command grant, principal mutation, direct manifest/source/ledger access; trusted intent writer |
| DISPATCHER | Kernel v0.3.2 outbox SELECT, narrow delivery state/claim columns | No payload/identity edits, insert/delete, audit or fs schema access; trusted publisher |
| MIGRATION OWNER (design only) | Own fs/fs_kernel objects and migrations, controlled DDL/grants; separate bootstrap administration | No runtime login membership, no shared runtime credentials; no automated owner provisioning |

Initiation and lifecycle profiles reuse the accepted A9–A11 privilege shapes. They are not complete API/transfer/administration profiles. Kernel outbox writer still needs UPDATE(event_id) for exact replay; lock-only service columns retain guards. No hostile-writer isolation claim. The dispatcher invokes the pinned Kernel publisher with an in-memory transport, not a live broker. Manifest capture and review use separate mapped actors even with a shared NOLOGIN privilege group. Revoke membership and disable principal; stop/drain in-flight sessions, invalidate credentials and terminate/recycle pools separately. No claim of immediate cancellation of already-running transactions.

## Isolated operational verification

- Profile provisioning, effective privilege matrix, direct column drift, inherited/non-inheriting membership drift and raw function drift; revoke and regrant; existing dispatcher session denied after membership revocation.
- Six competing initiations for three five-byte reservations; bounded futures and retry under repeatable-read contention; quota capacity invariant. Owner-held quota fence causes bounded worker settlement failure, no partial recovery/ledger/event writes; release lock and three recovery workers settle exactly once per session.
- Audited capture under conflicting source lock fails without evidence; three concurrent distinct capture IDs, exact receipts; disable/revoke blocks replay. No historical backfill.
- Logical pg_dump/pg_restore into another database in the same disposable PostgreSQL container, excluding ownership and ACLs; repeat migration, preserved upload, disabled principal and receipt/audit/outbox joins, zero quota rows. This is not a production-sized restore, point-in-time recovery, cross-cluster disaster recovery, backup encryption or RPO/RTO certification. Cluster-global roles are not backed up; restored principal rows may reference existing logins. Restore into quarantined network/database, disable principal mappings and dispatch before any connection, review effective grants and reconcile pending outbox/leases before controlled startup. No provider I/O.

## Production activation checklist (all gates remain closed)

1. Owner approves exact release, migration order, maintenance window and rollback/forward recovery plan; prove production-sized upgrade and restore from encrypted retained backup; record RPO/RTO and recovery ownership.
2. Inventory all service roles and effective direct/inherited/PUBLIC grants, including default ACLs, database CONNECT/TEMP/CREATE, sequences, schema ownership, grant options, role SET/ADMIN, functions, security definer owners and search_path. Separate migration owner, runtime/API/transfer/admin, worker, manifest capture/review and dispatcher; no privileged role inheritance. Review PostgreSQL version and managed-service limits.
3. Provision credentials via approved secret manager, no plaintext repository credentials; test rotation, disable/revoke, pool draining and break-glass access. Explicitly map tenant/actor/capability and mode after V21.
4. Rehearse representative concurrency, lock timeout/serialization rates, quota and manifest overhead, large data volume, sustained load and failure recovery; define SLO, alarms, runbooks and capacity thresholds from evidence.
5. Verify IAM, storage-provider credentials, scanner, network, outbox dispatch/reconciliation, monitoring and operational access. Approve production policy limits/durations, historical accounting execution, hold release, physical purge and deployment separately; none authorized here.
6. Collect exact commit, test logs, role inventory/diff, backup restore evidence, risk sign-offs and owner approval before any production rollout. No actual historical backfill, policy activation, hold release, provider deletion or deployment; PR #87 excluded.


2026-11-16 (Asia/Jakarta): owner approved PR #97; squash merged `fb606a7698df1bbef789efd51732178867ebaa95`; [exact-head CI 43824603192](https://github.com/vyrnforge/file-storage/actions/runs/43824603192) passed 212 tests. FS-310A13 authorized: scoped metadata/transfer/backend administration profiles, V22 lock-only identities, extended privilege drift, isolated credential rotation and quarantined restore/dispatcher restart, staging approval package. [Contract](42_RUNTIME_ROLE_ACTIVATION.md). CI and owner review pending. No production provisioning, historical backfill, production limits/durations, hold release, provider deletion or deployment; PR #87 excluded.
