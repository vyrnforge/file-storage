# FS-310A18 — Release assurance and staging handoff

Status: **draft preparation; NO-GO**. Continuation requested 2026-11-16 (Asia/Jakarta). This increment prepares decisions and evidence intake, not deployment. A17 PR #104 remains open; no owner merge or attestation dispatch approval has been recorded for it. This branch is stacked on A17 for review and must be rebased/retargeted to the accepted main baseline after A17 approval. Refs #13/#16/#33/#35/#36. PR #87 excluded.

## 1. Evidence checkpoint (A17, not a release)

| Item | Recorded evidence |
|---|---|
| A16 main baseline | b9420b5d4ef507be32328040b5ea11ad3c330308 |
| A17 PR head | 9b5990d726314229cee7056844baf1fd86d830b6 |
| A17 CI checkout | 382f7f6bab4217f99348ce20b245f836692c939d (PR merge checkout, not main) |
| CI | [43839879275](https://github.com/vyrnforge/file-storage/actions/runs/43839879275), completed success; 224 Java tests and five gate self-tests reported |
| Runtime JAR SHA-256 | cb384e7935d1987f2bffe9c2f289bffe3c6a3af8c610abc9d42121513ca8ae7b |
| SBOM SHA-256 | 652394e1450d28c7864f11103ec10f806be0d6776038b17aa3c8d5f54b520b6c |
| Evidence archive | [14213105973](https://github.com/vyrnforge/file-storage/actions/runs/43839879275/artifacts/14213105973), SHA-256 1da81d748275524275b74a1ffdeb6bfa2f658508837cb43598821f654c80d24a |
| Notice archive SHA-256 | c02262766049d559bbe6891f9e4a2aa514627ec1dc26e99d3b963aa21e09e46f |
| OSV | 2.7.1, Maven database fetched 2026-11-16T09:30:19Z; SHA-256 c7adfd1da096969751bf014817a5948d59241487937119413107037fc94e4ddf |
| Scan scope | 127/127 packaged libraries; 133/133 formal BOM components; zero reported advisories, zero unmapped packaged libraries; seven A16 findings absent |

Live repository PR/run state and published artifact digest checked at preparation. Test counts, nested digests and license inventory counts are from the recorded A17 evidence; A18 has not independently unpacked that archive or rebuilt the runtime. Retention is 14 days; preserve approved evidence in an access-controlled store before expiry, without publishing a release. A new main build can have different digests; never reuse PR digests as its release identity.

## 2. Distribution review: proposed dispositions, not legal approval

A17 evidence covers 127 packaged libraries: seven first-party, two historical Apache-2.0 corroborations, 112 declared-review-required and six multi-license-review-required. The six graph-only starter components require graph review but are not six extra nested JARs. 174 original notice/license documents are reported. Actual license text, not Maven labels or broad project license claims, governs. No third-party license clearance is granted here.

| Review family | Proposed treatment / verification |
|---|---|
| First-party Apache-2.0 | Retain repository LICENSE and applicable NOTICE/attributions; identify modified files and source distribution obligations if source is shipped. Verify each packaged module against its source and exact license. |
| Alibaba KMS 2.11.0 / RAM 3.1.0 | Preserve exact source archives' SHA-256 and checksum evidence and 145/145 and 198/198 Apache headers respectively; inspect non-Java resources and upstream notices. Keep two missing upstream POM fields unmodified; corroboration is not a legal determination. |
| Ordinary declared licenses (112) | Review each exact component, including Bouncy Castle, JDOM and Plexus custom labels. Preserve license text, copyright and notices; determine exceptions, source/offer/relink obligations, modification status and any incompatible terms. Do not infer a license from a label alone. |
| JAXB and Jakarta Annotation | Inspect exact artifacts for CDDL/GPL alternatives and classpath exception where present. Prefer a permitted alternative only after verifying its full terms and the distribution method; record selection and obligations, including any required source for covered files. |
| Javax Activation | Inspect exact dual-license terms and exception; record a permitted selection and covered-source obligations, if any. |
| Logback Core and Classic | Inspect actual EPL/LGPL terms separately for each artifact; choose a permitted alternative only after review of binary distribution, covered source, modifications, notices and relinking implications. |
| FastInfoset | Inspect exact EPL/GPL+classpath-exception or other supplied terms; record a permitted selection and covered-source requirements. |

Family rows are review starting points, not assertions about every version or license choice. License reviewer must resolve actual alternatives from each artifact, never convert an AND expression into OR. For all 127 packaged rows, retain a review record keyed by BOM ref, exact coordinate, nested JAR SHA-256, declared expression and original text/document hashes. Record selected license(s), reviewer/date/reference, distribution mode (internal staging, external binary, source, image as applicable), modifications, required notice/source/offer/relink actions, evidence of fulfillment, and disposition (PENDING / APPROVED_FOR_SCOPE / BLOCKED). No blanket approval; any missing or ambiguous term, unfulfilled obligation, or unidentified shaded component blocks distribution. Review graph-only components separately and scan native/shaded/OS/image content independently.

Final notice bundle: original unaltered texts plus root license, component-to-text attribution index, selected-license rationale and any required source/offer materials. Hash the finalized bundle and bind it to exact JAR/SBOM/notice evidence and distribution scope. Preserve original A17 collection separately; do not silently edit the CycloneDX metadata. Legal/owner approval required before external distribution. Internal staging authorization is separate and must explicitly consider its distribution mode.

## 3. Signed provenance handoff (not executed)

1. Owner explicitly approves PR #104 at its verified head; merge only then. Confirm actual main commit, tree, CI and approved review reference. Retarget A18 after merge; require full CI against main for its exact final candidate.
2. Owner separately authorizes manual `.github/workflows/staging-attestation.yml` on the exact approved main SHA with a non-secret approval reference. Confirm GitHub permissions/plan and reviewed CLI version (>=2.102.0); no dispatch on PR or arbitrary ref. Build parity inspected: the Makefile declares `ci: prepare-s3-fixture`, so the manual workflow's `make ci` already prepares the S3 fixture. No separate step required. A18 adds an explicit fail-closed check for installed stable GitHub CLI >=2.102.0 before either attestation action. It also binds the transferred raw OSV report and BOM to the transferred OSV metadata before signing. This is workflow preparation only; no manual run or signing has occurred.
3. Build job uses read-only permissions, full CI, fresh offline scan and evidence generation; signing job separately verifies transferred digests, run and source SHA. Do not replace bytes or use PR checkout evidence. Require zero reported Maven advisories, full coverage, no suppression and retain NO-GO decision.
4. Collect run ID/attempt, exact checkout/tree, JAR SHA-256, CycloneDX SHA-256, inventory, raw OSV/meta, license evidence and notice archive hashes, approved reference and signer identity. Verify provenance and CycloneDX predicate against the same JAR using exact workflow certificate identity, repository, main source ref and source digest, denying self-hosted runners. Independently compare the signed SBOM predicate with the exact retained BOM and record verification outputs and their digests. Store signed bundles and exact subject bytes under access controls; signing does not publish or deploy.
5. Reconcile rebuilt component versions and hashes with approved license dispositions; changed components require new review. A fresh main scan is mandatory and any new finding, incomplete scan or failed verification blocks admission. Do not equate an attestation with a vulnerability or licensing approval.

## 4. Staging environment handoff (all actual values outstanding)

Use [A16 admission form](../deployment/staging-admission-evidence.md), [bootstrap contract](45_STAGING_BOOTSTRAP.md) and [A15 operations](44_STAGING_OPERATIONS.md). Fill a private, access-controlled record with evidence references only; do not paste secret values or private endpoints into this public repository. Every owner/reviewer and actual evidence reference below is TBD.

| Responsible role (proposed) | Required input and acceptance evidence |
|---|---|
| Environment owner | Named non-production account/project, isolated network and test tenant/data classification; private ingress and approved egress; named service operator and separate authorization record. |
| DBA | Dedicated fs_staging database, version, migration identity separate from eleven runtime logins (METADATA, LOGICAL, PURGE_PREPARATION, PLACEMENT, BACKEND, GRANT_READ, READINESS, TRANSFER, WORKER, GOVERNANCE, DISPATCHER); opaque distinct references, V23, verify-full TLS, effective grants/ownership/PUBLIC/default ACL/role membership, revocation and restore evidence. Review rollback-only role plan before any approved application. |
| IAM owner | HTTPS Wyrmgate issuer/audience, trusted JWKS refresh origin and rotation, tenant membership/entitlements and consumer/policy/backend bindings, worker actor and negative tests. No example issuer or permissive fallback. |
| Storage owner | Dedicated S3 and OSS test targets with exact backend/location IDs, region/addressing, bucket/prefix and credential refs, unversioned and create-only behavior, no delete/migrate permissions, TLS/egress and bounded SDK/spool. Never repoint existing locations. |
| Scanner owner | Typed integration, real clean/malicious/unavailable and archive-expansion/timeout tests, hard resource limits and fail-closed quarantine. |
| Event owner | Isolated destination, credential ref, deadline and stable event-ID dedup/replay evidence. Dispatcher disabled until a separate replay decision. |
| Security/operations | Non-root identity, owner-only regular-file secret materialization, CA/JWKS refresh, ingress proxy TLS/loopback, firewall/DNS/egress, limits, alerts/ownership, representative workload, RPO/RTO and recovery drill. |
| Release reviewer | Exact approved main evidence, signed provenance, scope-specific license decisions and notices, shaded/native/OS/image review (image digest if used), residual risks and independent approval. |

No staging activation until actual values/refs reviewed, integration code reviewed and separate deployment permission granted. No provisioning or external smoke run in A18 preparation. If staging is authorized later, smoke negative issuer/tenant/consumer, upload/verify/scan/download, reader drain, logical delete/restore, revocation/readiness and bounded recovery; ensure no destructive queue/provider call. Keep dispatch disabled until replay approval. For failure: close ingress, stop/fence workers, preserve records, reconcile without deletion and use approved quarantined restore/forward fix. No production test data or credentials.

## 5. Decision register and admission

| Gate | Status at preparation |
|---|---|
| A17 candidate CI / security remediation | Verified as recorded; live CI success confirmed |
| A17 merge | Pending owner approval |
| A18 preparation | Draft, review pending |
| Per-component license disposition / final notice bundle | Pending exact-text review and scope-specific approval |
| Signed main provenance and SBOM | Not executed; separate authorization required |
| Actual staging inputs and integration evidence | Not supplied / not verified |
| Staging and production deployment | NO-GO; separate owner decision |
| Release publication, production policy/limits, historical accounting backfill, legal-hold release, physical provider deletion | Not authorized |

No runtime, migration, provider or ecosystem changes in this preparation. The only executable change is the manual attestation preflight (CLI version and raw OSV report/BOM digest checks); exact-head CI and authorized manual execution remain pending. Schema V23 retained. Approval of this document is not approval of any uncompleted gate. Owner decisions needed in order: A17 merge, scoped license review and exact-main attestation authorization, approved non-production environment inputs, then a separate staging admission decision. Record the actor, scope, exact commit/artifact/environment identifiers and evidence for each approval. Do not close master delivery trackers on a draft.
