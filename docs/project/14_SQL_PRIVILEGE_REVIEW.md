# FS-65 Q11 — SQL and effective privilege source review

Status: source-control review and isolated evidence, not a production ACL certification. Baseline `2273545a64a0b1c6cceb5eae85d2edb73e751454` (AQ-03A #111 accepted). PostgreSQL 17 disposable tests; V1–V23 unchanged. AQ-03B #112 separately handles failure observations; #113 addresses a confirmed source-level readiness gap. No new production grant, RLS, migration, ownership change or deployment.

## Migration ledger

The source inventory below records the Git blob at the accepted baseline and the principal privilege/invariant surface examined. This is a review of schema/routine/trigger authority, not a re-verification of every business transition or proof of live grants. Existing 231-test baseline and AQ-03A privilege evidence are the executable reference; actual environment verification remains separate.

| Migration | Blob (first 12) | Control surface / conclusion |
|---|---|---|
| V1 storage metadata | 016d342019f7 | Tenant/file/session composite FK identities; exact grant actor/consumer/action/purpose/scope; physical backend/location immutable trigger. Trusted writers, no RLS guarantee. |
| V2 configuration revision guards | d8831f5190ca | Sealing, immutable rule/history and forward active pointer, revision status transitions; invoker triggers. |
| V3 durable operations | 76b49ed6fff7 | Operation kind/state/lease constraints; worker duty, not direct provider authorization. |
| V4 backend retirement guards | 18613e4acdf3 | Reference admission lock and retirement fence; invoker backend_retirement_blocked(uuid) retains legacy PUBLIC EXECUTE, needs schema/table rights. Explicit known exception in fixture matrix; do not silently broaden or revoke without compatibility rehearsal. |
| V5 upload transfer claims | 4bf70e6aa41b | Digest-only claim handle, immutable fields, explicit transfer grant action; no bearer material in schema. |
| V6 upload integrity verification | 8453e9390569 | Claimed upload required, immutable verification and exact size; invoker trigger. |
| V7 upload scan lifecycle | 444f63c01cf2 | Bound scan evidence and version/session transitions, immutable evidence, no consumer scan verdict. |
| V8 terminal upload recovery | 214850c15d88 | Terminal evidence requires expiry/dead work; recovery transitions, no destructive cleanup enablement. |
| V9 download transfer claims | 95fba2901e52 | Exact version/tenant handle and one-use claim guard, separate download grant. |
| V10 metadata catalog | d4677ae0b46e | LIST_METADATA action and indexes; actual scope intersection in JdbcCatalog and JdbcGrants, not RLS. |
| V11 logical lifecycle | 8a90e1ee2604 | Logical state/revision/deleted_at and download revision pin; delete/restore grants. |
| V12 recovery retention safety | 1efc08d7e639 | Retention evidence and explicit lifecycle grant action; no production retention activation. |
| V13 purge preparation | d3a047ea20ea | Immutable preparation, database CHECK disables PURGE_VERSION execution. |
| V14 reader safety/simulation | 38f0cf64768a | Reader lease/ack evidence retained, immutable simulation transitions, claimed download cannot delete evidence. |
| V15 governed lifecycle | 0d89745327b0 | Independent review and immutable hold/governance/reconciliation records; no hold-release capability. |
| V16 isolated policy/quota | bc3fe6282069 | Immutable activation/observation, constrained reservation transitions. |
| V17 quota integration | fa0795bbc114 | Advisory/row fence, reserve/settle and consistency triggers; touch_quota_fence uses invoker rights (V18 revokes PUBLIC); settlement becomes definer at V21. |
| V18 governed quota review | 202463514b55 | Independent policy/finding reviews; explicit PUBLIC revokes on new tables/functions, bounded fence lock timeout. |
| V19 historical accounting manifest | 9ae0e936233f | Accounting epoch trigger SECURITY DEFINER; capture/review/read use pinned search_path, session_user + tenant + capability mapping; immutable history, PUBLIC revokes, bounded locking/manifest scope. |
| V20 manifest intents | 744f06195b44 | Audited manifest command derives actor from mapping; deferred check requires actual Kernel audit/event intent; PUBLIC revoked; no retrospective fabrication. |
| V21 execution boundary | d2f431ce6919 | REHEARSAL/AUDITED mode, session_user/tenant/capability, deferred audited receipt against raw grant path; identity-only UPDATE fences; quota settlement SECURITY DEFINER pinned search_path and PUBLIC revoke. |
| V22 transfer lock identity | 2d2e7767a1af | Version/session/operation identity UPDATE triggers for lock-only column privileges. |
| V23 retention evidence lock identity | 042518cea130 | Retention evidence file_id identity guard for shared lock; not permission to mutate hold/evidence. |

## Effective role and deployment boundary

- Eleven explicit login bindings, same database tuple, no owner fallback, runtime checks actual session_user/current_user, privileged role attributes, selected dangerous memberships, schema/database creation, duty sentinel, both histories and read-only migrations. Database tuple is sanity check, not cryptographic cluster identity. StagingRoleDataSource uses mounted role-specific secret, TLS verify-full, bounded connect/socket timeout, fixed JDBC destination and no alternate login API. Connection settings and server identity remain operator-reviewed.
- V4 disposable role matrix checks effective table/column/schema/function/sequence rights and grant options, login/group membership edges, relation/routine/schema ownership, database/public schema defaults, explicit default ACL. AQ-03A tested injected extra SELECT, sequence grant, PUBLIC routine EXECUTE, routine ownership, ADMIN membership and restoration. It checks all File Storage SECURITY DEFINER routines have fixed pg_catalog,fs,pg_temp search_path and no PUBLIC EXECUTE. Kernel schema and external extensions are separate inventory surfaces. Invoker trigger routines rely on trusted runtime role ACL and immutable constraints; table/column rights are not a tenant isolation boundary against a hostile database writer.
- Review-only staging-role-plan.sql creates NOLOGIN profiles and ends ROLLBACK; StagingRolePlanTest compares exact grants to fixture profiles. Actual login membership, migration owner, default ACL, connection policy and target ACLs must be reviewed before application. No automatic production permission changes.
- Lock-only identity UPDATE and backend endpoint UPDATE exist to support PostgreSQL row locking; broader table UPDATE on storage_location is constrained by immutable physical identity trigger. No DELETE/TRUNCATE privilege should be inferred from a lock grant. Trigger-disable/ownership/parameter authority would undermine these assumptions.

## Findings and next evidence

| ID | State | Finding / next action |
|---|---|---|
| Q11-01 | Confirmed source gap; correction #113 pending | RuntimeDatabaseRoles.ready claimed universal Flyway history write prohibition but only has_table_privilege detects table-level INSERT/UPDATE/DELETE/TRUNCATE. Column-only rights and non-inheriting reachable roles can escape. Candidate checks both exact relations, table and any-column writes plus membership-reachable ownership/write authority. Disposable tests prove direct/inherited column rights, NOINHERIT SET ROLE and restoration; require exact-head CI. |
| Q11-02 | Known compatibility residual | V4 invoker backend_retirement_blocked(uuid) retains PUBLIC EXECUTE; existing matrix explicitly tracks, schema/table ACLs constrain data. In future separate hardening evaluate explicit EXECUTE only for necessary backend role and existing callers, including restore quarantine. No migration or grant change here. |
| Q11-03 | Open environment and test evidence | Review effective session_replication_role and parameter SET/ALTER SYSTEM grants (pg_parameter_acl), search_path/role/database settings, external writable schemas, temporary object shadowing and extension routines. Fixed definer search_path already tested. Actual privilege to disable triggers or change protected parameters is not proven absent by the current sentinel/matrix. Add targeted disposable drift tests before environment admission. |
| Q11-04 | Open environment evidence | Inventory all actual login/group/role grantor paths (inherit/set/admin options), ownership/alter privileges, external schema/cluster predefined roles, database CONNECT/TEMP, role defaults and grants, pg_hba/TLS/network/pool reset and break-glass. Runtime sentinel deliberately cannot certify full least privilege. Require operator-reviewed immutable evidence, not a blanket grant. |
| Q11-05 | Open operational evidence | Readiness and grant checks are at check time, not revocation/cancellation of in-flight transactions, active streams or already acquired credentials. Actual session/pool termination, authority freshness and live IAM/provider rotation remain deployment gates. |

No confirmed need to change V1–V23 from the source-level review beyond Q11-01 runtime admission correction. Q11 remains open until exact-head remediation verification and residual evidence/owner disposition. No independent specialist sign-off claimed.


## Q11-01 verified candidate — owner merge approval pending

PR #113 head fb8f5d8546ba705c13542f325a861e654630be66, base 2273545a64a0b1c6cceb5eae85d2edb73e751454, synthetic checkout 78fc88a1da6d95b00915d677da453b189d2289f3 exact parents and identical tree. CI 43919474338 and audit 43919474214 attempt 1 SUCCESS; actual Maven logs both 231 tests (8 domain,11 SPI,13 S3,13 OSS,8 application,15 security,58 persistence,105 runtime), zero failures/errors/skips. Existing separated composition scenario expanded, no tests removed. A17 gate passes, release NO-GO. Actual PostgreSQL 17 assertions prove table privilege false and any-column privilege true for direct UPDATE checksum in fs history and inherited INSERT version in fs_kernel history, reachable NOINHERIT SET ROLE membership denied even with direct effective column privilege false; each grant revoked and readiness/approved ACL matrix restored. No actual history mutation, migration, grants or production deployment. Runtime check conservatively rejects every membership-reachable owner/table write/column INSERT/UPDATE on both exact history relations; not full ACL certification.

Audit artifact 14253531606, 1,242,760 bytes, SHA256 independently verified fa93a54a3e24f20c96f9ef2c147363011c00f92722dc6e8f9504be4b3af4c9d0; 438 ZIP entries CRC clean, checkout exact. Baseline 14250581146. 67/67 SpotBugs occurrences identical including occurrence number, module/rule/hash/priority/rank/class/method/field signatures, zero added/removed, zero errors/missing classes. JaCoCo line 4121/4496 ->4122/4496, branch 2177/3279 ->2178/3279, instruction 31758/34865 ->31764/34865, methods/classes unchanged; only unchanged S3StorageProvider coverage varied (same existing unordered overlap fixture). Source inventory 154 files: only RuntimeDatabaseRoles and ApiRuntimeCompositionTest changed; all 23 migrations, POMs and public contracts unchanged. Q11-01 verified in isolated scope; Q11-02–05 residual ledger remains open. #113 unmerged pending owner approval. #112 sibling must be reconciled and reverified after first merge. #107/#87 untouched; no A20, signing, publication or deployment.
