# FS-310A14 — Separated runtime composition and staging admission

Status: candidate; CI and owner review pending. A13 PR #98 accepted, merge 8e48e5ad9c6789b8b7e11ca858b185f56eb92630, exact-head CI 43826187384 passed 215 tests. Refs #13/#16/#33/#35/#36.

## Composition contract

The existing API composition has explicit separated-role mode via RuntimeDatabaseRoles and ApiRuntimeDependencies. Ten distinct expected login bindings: METADATA, LOGICAL, PURGE_PREPARATION, PLACEMENT, BACKEND, GRANT_READ, READINESS, TRANSFER, WORKER, GOVERNANCE. The API facade routes initiation/read to metadata, reversible lifecycle to logical, immutable purge preparation to its own source; grant lookup uses JdbcGrants read-only source; placement and backend administration have separate sources; readiness reads both Flyway histories. Internal factory methods construct upload/download transfer repositories, completion work and governed draft/review/hold/reader proof adapter using their bound sources. No auto-registration of transfer/worker routes, schedulers, providers, or governed policy HTTP endpoints. Legacy single-source constructor retained for isolated existing tests; `fs.require-separated-roles=true` rejects legacy API composition at startup. Default executable remains health-only and loopback-bound; COMBINED readiness still false. No production start, deployment or external listener.

Admission checks actual session_user/current_user, distinct expected logins, same database name/OID/server-address/port tuple, no owner membership, superuser/createdb/createrole/replication/bypassrls or selected built-in escalations, schema CREATE/database CREATE, required role sentinel and no history writes, then nonmutating migration validation. Readiness repeats role checks and authority probe; failure returns DOWN without leaking credentials. This is not cryptographic cluster identity or exhaustive runtime ACL proof. DataSource connection timeout/transport and pool reset remain deployment responsibilities; existing sessions and privilege changes after checks are not automatically terminated. Existing HTTP calls still rely on their authorization and DB privileges, not readiness as a request filter.

## Isolated V3 profiles and migration

V3 test-only profile matrix adds LOGICAL, PURGE_PREPARATION, PLACEMENT, BACKEND_ONLY, GRANT_READ, READINESS, GOVERNANCE, COMPLETION_WORKER. A13 combined backend profile retained for historical regressions. Placement and backend write privileges separated. Governance permits immutable draft/review and protective hold and reader review, never release or production activation. Grant administration issuance/revocation remains an external trusted authority/operator responsibility; no issuance endpoint exists; read-only adapter cannot grant/revoke. Isolated quota activation, historical manifest and dispatcher remain their separately reviewed A12/A13 paths; no new activation wiring. Worker completion additionally needs integrity INSERT and observed fields UPDATE; evidence guard still enforces immutable verified evidence. Purge preparation only writes review request, not executable operation. V23 guards lock-only file_retention_evidence.file_id, other evidence writes denied to preparation profile. No new provider deletion, historical backfill, hold release, production policy or deployment. Populated V22→V23 upgrade/repeat.

## Evidence and admission decision

Candidate regression: actual HTTP API using ten restricted logins, scoped placement/backend read and authorization, initiation, transfer claim, completion work verify/scan, logical delete/restore, immutable purge preparation with synthetic legacy CLEAR evidence, governed draft/review/protective hold, grant-read denial, retention identity denial, revoked grant source readiness and access denial, wrong expected login/owner/legacy startup rejection. Synthetic identity/authority/provider evidence, no network provider I/O in this regression.

**Staging go/no-go: NO-GO until owner separately approves and operational evidence is supplied.** Repository and CI readiness is not an environment readiness assertion.

| Gate | Required evidence | Status before external rehearsal |
|---|---|---|
| Source / build | Exact approved commit, pinned dependencies, CI, SBOM and vulnerability disposition | CI pending; SBOM/release review outstanding |
| DB | Isolated non-production cluster and owner, V23 rehearsal, complete role/ACL inventory and credential refs, bounded connection settings | disposable evidence only |
| IAM | Wyrmgate test issuer/audience, membership, entitlement and consumer/admin bindings; no production identity | synthetic only |
| Provider | Dedicated test S3 and native OSS locations, exact target allowlist, secret-manager references, no production credentials or data | test adapters verified in existing suite; staging refs outstanding |
| Scanner / workers | Explicit trusted scanner and limits, worker schedule/leases, no purge/migrate execution | synthetic scan evidence; real staging wiring outstanding |
| Network | Private service ingress, loopback executable boundary/reviewed proxy, TLS, egress allowlist | design only |
| Recovery / monitoring | Quarantine restore, replay decision, outbox dedup, alarms, lock/load/SLO, rollback/forward recovery | A12/A13 disposable rehearsal; representative environment outstanding |
| Approval | Exact environment, owners, configuration evidence and explicit staging deployment authorization | not approved |

No production retention durations/quota limits, historical accounting backfill, legal-hold release, actual provider deletion or production deployment. PR #87 excluded.


2026-11-16 (Asia/Jakarta): owner approved PR #100; squash merged `5ea7608abd0961361359035be742d24e93cd0a1e`; [exact-head CI 43829959343](https://github.com/vyrnforge/file-storage/actions/runs/43829959343) passed 216 tests. FS-310A15 authorized: explicit staging runtime with eleven separated identities, exact provider pin/ref admission, bounded verification/scan/recovery and gated dispatcher, packaged S3/OSS adapters, runtime inventory and staging runbook. [Contract](44_STAGING_OPERATIONS.md). CI and owner review pending. No deployment, production policy, historical backfill, hold release or physical deletion; PR #87 excluded.
