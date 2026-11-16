# Staging admission evidence — FS-310A16

Status: **NO-GO — environment and security review outstanding**. This is a review record, not approval, credentials, an executable deployment or production configuration. Do not record secret values. Reference approved records or evidence hashes only.

| Gate | Evidence required before deployment approval | Current state |
|---|---|---|
| Target environment | Named staging account/project, isolated network, owner, tenant/test data classification, database name and version | Not supplied |
| Release | Exact approved commit, CI, JAR/SBOM hashes, license and vulnerability dispositions, signed provenance, image digest if used | CI candidate evidence and offline Maven scan; review pending |
| DB | Dedicated fs_staging, migration-owner and 11 login refs, V23, TLS CA/server name, explicit effective ACL inventory, role/membership/ownership drift and restore verification | Review-only role plan; no applied state |
| IAM | Wyrmgate staging HTTPS issuer/audience, approved JWKS refresh origin/key rotation, tenant membership/entitlement, consumer and backend/policy bindings and grants, negative identity tests | Typed ports; actual authority contract/refs not supplied |
| Provider | Dedicated test S3/OSS endpoints/regions, exact backend and location IDs, bucket/prefix, credential refs, unversioned and create-only behavior, account policy forbids delete/migrate, TLS/egress evidence | Actual factories; external targets not supplied |
| Scanner | Reviewed scanner integration, clean/malicious/unavailable, archive/resource limits, hard timeout, no permissive fallback | Typed port only |
| Event transport | Approved isolated broker destination, credentials, hard deadline, event-id dedup, replay decision; dispatcher remains disabled initially | Typed port only |
| Network | Private TLS ingress proxy to loopback, firewall and egress allowlist, DNS and certificate review, non-root process, mounted secrets/spool/CA/JWKS permissions | Design requirements; no deployment |
| Observability | HTTP and provider latency/error, denial, readiness, connection/lock saturation, pending/expired/dead jobs, scan failures, recovery HOLD, outbox age/dead, spool/disk, alerts and ownership | Metrics/alert integration and thresholds outstanding |
| Recovery | Representative load, restore quarantine and replay run, RPO/RTO, rollback/forward-fix decision, credential revocation and in-flight fence | Disposable regression only |
| Approval | Owner approves exact environment, release evidence, residual risk and staged activation plan | Not granted |

## Evidence handoff

1. Collect approved non-production records for the target environment; populate a private deployment-specific properties file outside the repository and materialize owner-only regular files. Never use example values. Implement the reviewed Integrations ports in environment deployment code; no arbitrary runtime plugin.
2. DBA reviews the V4 role plan against the actual V23 database and the complete effective ACL inventory, including PUBLIC/default ACLs and schema/function ownership. Provision only after separate authorization. Apply migration separately; never run as runtime. Verify actual TLS/identity, all eleven logins, role revocation, pool/session termination where applicable. Verify no production access.
3. Generate candidate evidence from CI, review the offline OSV scan status/advisories and all mapped and unmapped libraries, shaded dependencies and OS/base image if applicable with approved vulnerability data and license policy. Record scanner version/database timestamp, findings and disposition per CVE/package/license; absence of findings is not a guarantee. Sign approved artifact/provenance through approved release process; do not publish here.
4. Review ingress/egress, secret-manager materialization, provider-account privileges and hard network deadlines. Validate actual scanner and event transport contract; do not enable dispatcher until separate replay/reconciliation decision. Set staging activation only after approval.
5. Authorized staging smoke: liveness/readiness, wrong issuer/tenant/consumer denied, upload/claim/complete/verify/scan/download/drain, logical delete/restore, provider credential rotation/denial, worker outage/recovery, recovery HOLD, bounded dispatch and dedup in isolated transport, no destructive queue or provider calls. Record real logs/metrics and exact artifact/DB versions. Stop on failure, quarantine and reconcile; no physical deletion.
6. Reconcile pending work, drain ingress and scheduler, preserve immutable file identity, revoke credentials if necessary, restore quarantined only with approved recovery point, validate role/migration state before reopening. Actual SLO, RPO/RTO and alert thresholds approved by environment owner.

**Approval requested for repository candidate only.** Deployment, provider deletion, hold release, production policy/limits, historical backfill and publication are separate gates.


## A17 security gate update

A16 approved engineering baseline only, merged b9420b5d4ef507be32328040b5ea11ad3c330308. A17 pins patched crypto/telemetry and requires all formal/packaged Maven components scanned, zero reported advisories, exact digest bindings and no scan unavailability before candidate CI succeeds. License/notice evidence includes root license, nested notices and exact historical public source corroboration; legal/distribution dispositions remain separate. Manual main-only attestation workflow requires separate approved exact commit/approval reference; no signing or deployment performed by A17 PR CI. Environment and deployment decision remain NO-GO. See [A17 contract](../architecture/46_RELEASE_SECURITY_REMEDIATION.md).


2026-11-16 (Asia/Jakarta): owner approved A17 PR #104 exact head `9b5990d726314229cee7056844baf1fd86d830b6`; squash merged `eb98f1ea2f259e6a345c77117ada9f2237edb1c2`, CI 43839879275 passed 224 tests and zero reported Maven advisories. A18 PR #105 rebased onto main; [release assurance and staging handoff](../architecture/47_RELEASE_ASSURANCE_HANDOFF.md) adds exact-component distribution review, candidate notice bundle and strengthened attestation statement verification. V23 retained; full A18 CI and owner review pending. No license distribution approval, signing dispatch, publication, provisioning, deployment, production policy, historical backfill, legal-hold release or physical provider deletion; PR #87 excluded.


2026-11-16 (Asia/Jakarta): owner approved A18 PR #105 at exact head `9eca4ced4b2e66717ab484bfe58c36c1487845a2`; squash merged `c6713d6088a61ff67fae0dc16c16afd4da971c43`. CI 43846856194 passed 224 Java tests, ten distribution and six statement checks, 133/133 SBOM and 127/127 packaged Maven components, zero reported advisories. A19 authorized for remaining license dispositions and staging acceptance preparation only; [A19 contract](../architecture/48_LICENSE_DISPOSITIONS_STAGING_ACCEPTANCE.md). V23 retained. No distribution approval, signing, publication, provisioning, deployment, production policy, historical backfill, legal-hold release or physical provider deletion. PR #87 excluded. A19 candidate CI and owner review pending.
