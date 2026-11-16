# FS-310A18 — Release assurance and staging handoff

Status: **owner-approved engineering baseline; release NO-GO**. Owner authorized A17 merge and A18 continuation 2026-11-16 (Asia/Jakarta). A17 PR #104 squash merged eb98f1ea2f259e6a345c77117ada9f2237edb1c2 at the approved head; A18 PR #105 rebased onto that main commit. Signing execution, publication, provisioning and deployment remain separately gated. Refs #13/#16/#33/#35/#36. PR #87 excluded.

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

A18 independently downloaded A17 evidence archive, checked its published SHA-256, inspected all 127 license rows and checked 175 original notice records (174 nested-library records plus one runtime record) against exact archive bytes. Two records are binaries (outer nested third-party Jackson JAR and Bouncy Castle LICENSE.class), retained in original evidence but excluded from candidate text-only distribution bundle. A18 preserves the original Javax Activation 1.2.0 CDDL/GPL text with its reviewed ISO-8859-1 encoding and exact SHA-256; no transcoding. 42 libraries have no embedded notices (seven first-party, 35 third-party). A17 test counts remain recorded CI evidence; a fresh A18 build is required. Retention is 14 days; preserve approved evidence in an access-controlled store before expiry, without publishing a release. A new main build can have different digests; never reuse PR digests as its release identity.

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
| FastInfoset | Exact BOM Apache-2.0 and BSD-3-Clause; LICENSE Apache-2.0, NOTICE BSD-3-Clause. Retain both; resolve scope, not presumed OR. |

Family rows are review starting points, not assertions about every version or license choice. License reviewer must resolve actual alternatives from each artifact, never convert an AND expression into OR. For all 127 packaged rows, retain a review record keyed by BOM ref, exact coordinate, nested JAR SHA-256, declared expression and original text/document hashes. Record selected license(s), reviewer/date/reference, distribution mode (internal staging, external binary, source, image as applicable), modifications, required notice/source/offer/relink actions, evidence of fulfillment, and disposition (PENDING / APPROVED_FOR_SCOPE / BLOCKED). No blanket approval; any missing or ambiguous term, unfulfilled obligation, or unidentified shaded component blocks distribution. Review graph-only components separately and scan native/shaded/OS/image content independently.

Final notice bundle: original unaltered texts plus root license, component-to-text attribution index, selected-license rationale and any required source/offer materials. Hash the finalized bundle and bind it to exact JAR/SBOM/notice evidence and distribution scope. Preserve original A17 collection separately; do not silently edit the CycloneDX metadata. Legal/owner approval required before external distribution. Internal staging authorization is separate and must explicitly consider its distribution mode.

## 2a. A18 exact-component engineering review

The new `scripts/staging-distribution-review.py` checks all packaged path/hash/BOM mappings, provenance bindings, every original notice record and root license, generates a per-component engineering classification/obligations/evidence status, and retains text-only candidate notices plus attribution index. Source supplements use exact Maven Central sources JARs and published SHA-1, record SHA-256, extract bounded original license/notice texts, never execute source code. Version-matched upstream license/notice files are additionally fetched from exact immutable source commits using the checked-in source catalog and verified by Git blob SHA-1, with original bytes and SHA-256 retained. Project-tag evidence corroborates but does not establish binary-to-source reproducibility; declaration-only files and missing or conflicting evidence remain REVIEW_REQUIRED. No silent OR selection or rewriting third-party metadata. Six graph-only starters tracked separately. The candidate bundle is not an approved distribution bundle. Actual source availability/terms, covered source fulfillment, modifications, and legal/owner approval remain release blockers.

Concrete A17 evidence findings: javax.activation-api 1.2.0 BOM CDDL-1.0 versus embedded CDDL 1.1; FastInfoset dual metadata and different embedded LICENSE/NOTICE; Logback Core/Classic no embedded notices; DOM4J Plexus metadata with no embedded license; Bouncy Castle actual MIT-form text and binary license class; JDOM custom non-endorsement/naming conditions. Jakarta Annotation NOTICE conditions secondary GPL exception; JAXB API exact CDDL 1.1/GPL exception. Postgres includes embedded OnGres SCRAM/stringprep license texts; AWS third-party Jackson retains original notices. Shaded/native/OS inventory remains separate. Every row has a proposed obligation record; no blanket distribution approval.

## 3. Signed provenance handoff (not executed)

1. A17 merge authorized/completed and A18 rebased. Require full CI against main for final A18 candidate; owner must separately approve A18 merge and exact main signing dispatch.
2. Owner separately authorizes manual `.github/workflows/staging-attestation.yml` on the exact approved main SHA with a non-secret approval reference. Confirm GitHub permissions/plan and reviewed CLI version (>=2.102.0); no dispatch on PR or arbitrary ref. Build parity inspected: the Makefile declares `ci: prepare-s3-fixture`, so the manual workflow's `make ci` already prepares the S3 fixture. No separate step required. A18 adds an explicit fail-closed check for installed stable GitHub CLI >=2.102.0 before either attestation action. It also binds the transferred raw OSV report and BOM to the transferred OSV metadata before signing. This is workflow preparation only; no manual run or signing has occurred.
3. Build job uses read-only permissions, full CI, fresh offline scan and evidence generation; signing job separately verifies transferred digests, run and source SHA. Do not replace bytes or use PR checkout evidence. Require zero reported Maven advisories, full coverage, no suppression and retain NO-GO decision.
4. Collect run ID/attempt, exact checkout/tree, JAR SHA-256, CycloneDX SHA-256, inventory, raw OSV/meta, license evidence and notice archive hashes, approved reference and signer identity. Verify provenance and CycloneDX predicate against the same JAR using exact workflow certificate identity, repository, main source ref and source digest, denying self-hosted runners. The new attestation statement check compares the verified SBOM predicate JSON structurally with the exact retained BOM and validates the exact JAR digest in both statements; signer digest is pinned to approved main SHA as well. Record verification outputs and their digests. Store signed bundles and exact subject bytes under access controls; signing does not publish or deploy.
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
| A17 merge | Approved and merged eb98f1ea |
| A18 preparation | Approved and merged c6713d6; exact-head CI 43846856194 passed |
| Per-component license disposition / final notice bundle | Pending exact-text review and scope-specific approval |
| Signed main provenance and SBOM | Not executed; separate authorization required |
| Actual staging inputs and integration evidence | Not supplied / not verified |
| Staging and production deployment | NO-GO; separate owner decision |
| Release publication, production policy/limits, historical accounting backfill, legal-hold release, physical provider deletion | Not authorized |

No runtime, migration, provider or ecosystem changes in this preparation. Executable changes are release-evidence generation and manual attestation verification only. CI runs distribution integrity self-tests and statement-policy self-tests; the signing job additionally binds the build run attempt. Exact-head CI and authorized manual execution remain pending. Schema V23 retained. Approval of this document is not approval of any uncompleted gate. Owner decisions needed: A18 merge after CI, scoped license distribution approval and exact-main attestation authorization, approved non-production environment inputs, then separate staging admission. Record the actor, scope, exact commit/artifact/environment identifiers and evidence for each approval. Do not close master delivery trackers on a draft.


2026-11-16 (Asia/Jakarta): owner approved A18 PR #105 at exact head `9eca4ced4b2e66717ab484bfe58c36c1487845a2`; squash merged `c6713d6088a61ff67fae0dc16c16afd4da971c43`. CI 43846856194 passed 224 Java tests, ten distribution and six statement checks, 133/133 SBOM and 127/127 packaged Maven components, zero reported advisories. A19 authorized for remaining license dispositions and staging acceptance preparation only; [A19 contract](48_LICENSE_DISPOSITIONS_STAGING_ACCEPTANCE.md). V23 retained. No distribution approval, signing, publication, provisioning, deployment, production policy, historical backfill, legal-hold release or physical provider deletion. PR #87 excluded. A19 candidate CI and owner review pending.
