# Decision and change register

Owner approval recorded 2026-11-14 (explicit conversation instruction). DEC-FS001–010 and roadmap approved; DEC-FS011 public and Apache-2.0 approved; release version scheme remains open. FS-100A authorized subject to prerequisites. Subsequent owner direction recorded below; no blanket later implementation or merge authorization.

| ID | Decision | Status | Rationale / open issue |
|---|---|---|---|
| DEC-FS001 | Independent file domain, selective modernization | Approved | Preserve proven behavior; owner review of reuse |
| DEC-FS002 | Single product repository, modular architecture | Approved; runtime refined by DEC-FS012 | Avoid separate business ownership; independent API/transfer/worker roles |
| DEC-FS003 | Stable file ID, separate physical locator, pinned sessions | Approved | Safe provider change and migration |
| DEC-FS004 | Console-managed backend/location/policy revisions | Approved | No consumer deployment for new configured backend |
| DEC-FS005 | Compiled S3 and native OSS adapters, capability matrix | Approved | No arbitrary runtime executable plugins |
| DEC-FS006 | Kernel 0.3.2 and IAM boundary; optional JDBC | Approved | Shared contracts, domain owns policy |
| DEC-FS007 | Java 21/Maven/Spring Boot/PostgreSQL/Flyway | Approved | Legacy compatibility and kernel fit; exact framework/database/SDK versions later |
| DEC-FS008 | Client in service repo; optional kernel adapter later | Approved | Avoid cyclic dependency and extra distribution |
| DEC-FS009 | Private-by-default data, public repo only sanitized docs | Approved | Security and least privilege |
| DEC-FS010 | Legacy migration via registered location, no forced byte copy | Approved | Preserve IDs; confirm actual data/compatibility |
| DEC-FS011 | Apache-2.0, public repository | Approved; version scheme open | Owner confirmed public and Apache-2.0; release numbering later |
| DEC-FS012 | Independent transfer gateway, API/worker roles, optional private CDN | Approved by owner 2026-11-15 | Isolate byte traffic from API, retain private placement and scalable provider-neutral contracts; see [transfer contract](../architecture/10_PROVIDER_TRANSFER_CONTRACT.md) |

Decision approval must record date, approver, rationale, alternatives, consequences and affected contracts. Never change status from proposal based on assistant inference.

## Change log
2026-11-14: new repository bootstrap; initial Phase 0 documents and reserved directories. No service code, dependency lock, runtime migrations or deployment. No architecture decision accepted by this change alone.

2026-11-14: owner reports GitHub settings applied; active ruleset and CI merge gate verified. [Phase 0 decision package](06_PHASE_0_ARCHITECTURE_REVIEW.md) refines recommendations and [FS-100A](07_FIRST_IMPLEMENTATION_INCREMENT.md) is ready for review. No ADR approved by inference. See [security evidence](../security/REPOSITORY_SECURITY_EVIDENCE.md).

2026-11-14: Owner explicitly approved DEC-FS001–010, roadmap, recommended stack, public visibility and Apache-2.0, confirmed remaining security settings, and authorized FS-100A conditional on prerequisites. Exact framework/database/SDK versions, release numbering, legacy migration and later increments remain open. Approval rationale/alternatives in [review package](06_PHASE_0_ARCHITECTURE_REVIEW.md); no additional instructions.

2026-11-14: Owner directed continuation based on requirements using Product & Business Analysis after FS-100A merge. Interpreted as authorization of next bounded FS-100B application foundation per approved roadmap, not new policy exceptions, later phases or deployment. See [outcome/rules](09_PRODUCT_OUTCOMES_AND_RULES.md) and [acceptance](10_FS100B_ACCEPTANCE.md). Unresolved product policy remains owner-controlled.

2026-11-14: Owner completed FS-100B review and directed proceeding to next increment. PR #6 merge verified. Interpreted as FS-110 authorization within approved DEC-FS006/009, not new policy exceptions or later work. FS-110A implements deny-by-default exact grants and trusted verifier/binding ports; live issuer/audience, machine identity, grant administration and production integration remain unresolved (FS-110B). See [security contract](../security/FS110_IDENTITY_AUTHORIZATION.md).


## Approved dependency maintenance amendment to DEC-FS006
2026-11-15: Owner authorized completion and merge of the kernel remediation adoption. Upgrade from io.vyrnforge.platform:platform-kernel:0.3.0 to io.github.vyrnforge:platform-kernel:0.3.2 across all current modules including FS-110A security. Rationale: use verified signed public artifact and audit fixes; alternatives retain unpatched artifact or source candidate. Preserve Java imports, v0.2 wire contracts, opaque IDs (512-character released FileReference limit), domain ownership and existing security rules. Remove old manual bootstrap; use anonymous Maven Central and strict checksums. PR #8 initial FS-100B CI passed 18 tests; fresh combined CI required. No authorization of later product increments or deployment.

2026-11-15: Owner directed next after PR #7 merged. Implement FS-110B verifier slice only. Implementation choice: Nimbus JOSE+JWT 10.10 (Apache-2.0), explicit trusted RSA key snapshots, exact single-client access-token profile matching pinned Wyrmgate issuer. Dedicated File Storage client registration is proposed deployment direction, not applied or approved IAM configuration. No new machine/delegation policy, cross-repo changes, deployment or FS-120 authorization.
2026-11-15: Resolved PR #9 conflicts against merged PR #8; retain approved Kernel 0.3.2 Central coordinates and all FS-110B changes. No new scope, merge or deployment authorization.

2026-11-15: Owner directed next after POM maintenance PR #10 merged. Interpret as FS-120A only. Use PostgreSQL 17 for disposable CI, Flyway 13.12.1, pgjdbc 42.7.14 and Testcontainers 1.21.4 aligned with Kernel v0.3.2; no production major/version selection or deployment. Service migrations in separate classpath location and schema fs, avoid Kernel migration version collision. JDBC adapter persists initiation/read aggregate only, explicit transaction and tenant-scoped lookups; use Kernel reliability transaction in FS-120B/C rather than duplicating audit/outbox/idempotency lease logic. Technical identifier lengths and timestamp precision documented in persistence contract.

2026-11-15: Owner confirmed PR #11 merged after recommendation to proceed FS-120B. Interpreted as next bounded FS-120B1 only, not blanket scope or merge authorization. Adopt published platform-kernel-jdbc 0.3.2, separate fs_kernel schema/history, explicit lease TTL, versioned actor/consumer-bound hashed key and fingerprint; stored result only session ID, replay reauthorizes and reloads. Existing initiation_request permanent uniqueness remains (even after lease expiry); retention policy deferred. B2 config CAS/grants and C audit/outbox remain separate. No production migration or external changes.

## Delivery planning record — 2026-11-15
Owner confirmed PR #12 merged and requested the Product & Business Analysis / Technical Delivery Lead work breakdown before advancing. [Master delivery tracker #13](https://github.com/vyrnforge/file-storage/issues/13) and work issues #14–#50 map the approved roadmap to bounded acceptance and evidence packages. FS-120B2A (#20) is the recommended next candidate; no additional runtime increment, product policy, production target, named staffing assignment, release date or deployment approved in this planning step. Existing decisions and outstanding gates remain in force.

2026-11-15: Owner instructed next implementation with Backend & Domain Engineering, authorizing FS-120B2A #20 only. Reuse Kernel 0.3.2 JdbcReliabilityTransaction; read-committed control-plane CAS locks plus existing repeatable-read initiation snapshots. V2 seals existing policies; new publication advances expected+1, rollback is a new revision, backend activation drains previous revision, initial activation expected=0 only with no history. No direct reactivation/retirement or production admin authority. No new dependency or policy exception.

2026-11-15: owner reviewed #20, explicitly instructed merge and proceed. PR #52 squash merged at 32b387cd1b3ee2113832e4eff0ab224da53926d0; #20 closed with hosted CI 43668967693. #21 authorized only: use existing fs.file_grant and SecuredFiles.Grants, no schema change; persistence depends on security port (one-way), fresh committed read per check, configurable query timeout/result bound, fail closed on overflow/source failure. No in-flight revocation linearization, issuance or admin API; #17/#19 and #22 remain separate.

2026-11-15: owner approved proceeding and moving to next issue; interpreted as merge of reviewed PR #53 and authorization of #22 only. Squash merge 137ebeab9a8e83b33219f9d3ae3ccc83f775abd1. FS-120C1 uses Kernel v0.3.2 AuditEvent/EventEnvelope and existing JDBC writers; internal event v1 for initiation, backend activation, policy publication. Minimal identifiers/revisions, canonical actor without issuer/subject/display name; explicit trusted actor/correlation for admin commands. No read/denial audit policy approval, event publication, grant issuance, production migration or #23 authorization.

2026-11-15: owner reviewed and explicitly directed merge PR #54 and next issue. Squash merge 271403d9eb968a2224f7cab963da486b3b0eca89; #22 closed, #23 authorized. Adopt Kernel JdbcOutboxPublisher 0.3.2 with bounded ticks and neutral transport error, service V3 typed operation ledger, immutable request identity via unique tenant/kind/key and target match, persisted attempt budget, claim token/expiry CAS and bounded dead inspection. No auto redrive, live broker, provider effects, scheduling, global capacity/defaults or public worker API; future handlers must authorize/reconcile and fence metadata completion. No #24 authorization.

2026-11-15: owner explicitly instructed merge PR #55 and proceed next. Squash merge b5e8159843fb41121eeabe309497899c3bcd7257; #23 accepted. #24 authorized for contract/test work only. Reusable test-only SPI classifier and stricter metadata/byte semantics specified; private service-controlled streaming/tickets proposed (DEC-FS012, pending). No direct-provider consumer grant exception, actual SDK version/profile, production limits or later implementation approved. See provider transfer contract.

Owner approved DEC-FS012 on 2026-11-15: independent transfer gateway and separate API/worker runtime roles, optional private CDN acceleration. One product, repository, domain and authoritative metadata store; combined local/small deployment permitted. API authorizes/orchestrates, never carries consumer file bodies in separated profile. Gateway streams to private providers, workers verify/scan/reconcile. No direct provider URL, locator, signature or credential exposure. CDN conditional, no shared confidential caching by default. No production deployment or limits approved.
Alternatives reviewed: in-process streaming (combined profile only), direct provider URLs (rejected under privacy rule), dedicated gateway (selected), private CDN (conditional). Gateway still transports bytes; latency/cost improvement not measured. Affected #24/#25/#30/#31/#35/#42–#44/#57/#58. Owner authorizes #25 after relevant prerequisites; production profiles, limits, CDN vendor and deployment remain unapproved.

2026-11-15: owner confirmed both PRs reviewed; interpreted as acceptance of #56 and #59 only, consistent with prior workflow. #56 squash merged at c2c3c9a8a5f18a6d66f859097ae3031ec73bb508. #59 isolated S3 profile uses AWS SDK 2.57.2 and official checksum-pinned MinIO binary locally containerized after Quay and Docker Hub pull failures; CI 43675256063 passed 91 tests. No production profile approval or later issue authorization.

2026-11-15: #59 accepted/merged at e567b2fc4df8ac4fcdcdde549c02cea3eaab9e71 (91 tests final CI 43675532564). Owner authorized #26 only. Native OSS SDK 3.18.4, V4 signing, unversioned single-PUT with native forbid-overwrite, bounded staging/request executor, explicit path/virtual-host addressing, CNAME blocked until bucket-bound evidence. Local loopback fixture exercises actual SDK HTTP; no cloud staging or full legacy parity claim. No production deployment or #27 authorization.

2026-11-15: owner explicitly authorized merge PR #60 and proceed #27. Squash 972e091004725f1d3eef11d550fa773e4897545e; local CI 43676549629 passed 103 tests. #26 open for actual staging/#14 parity. #27 bounded registry: initial unvalidated location set, syntactically normalized endpoints and namespace overlap check for same known endpoint/bucket, immutable credential revision CAS, fresh DB reads rather than cache, exact existing-access credential resolution port outside DB transaction, Kernel atomic audit/events. No chosen production secret store, live resolver, provider validation, admin auth or #28 authorization. Adding locations to previously validated backends requires revision-aware validation design in #28; no silent admission.

2026-11-15: Owner accepted PR #61 and directed merge and next increment. Merged `224eada`; scope interpreted as #28 bounded internal lifecycle, not approval of #16 credential/network policy, production deployment or #29. Explicit drain follows existing one-way lifecycle and activation CAS; V4 conservative backend-wide reference guards and stale-snapshot fencing in review. Exact draft validation requires deployment-approved target and trusted per-location checks; concrete provider/authority and evidence lifetime remain gated. No new ADR approval inferred.

2026-11-15: Owner accepted PR #62 and directed merge and next increment. Merged a4d9e19; authorized #29 FS-220 internal policy control, not #16 production decisions or #30 runtime. Reuse existing fail-closed priority semantics, require all-rule location authorization and reauthorize rollback into a new revision. No ADR approval inferred.

2026-11-15: Owner accepted continuation after PR #63 verification and merge (1d05534), #30 isolated runtime only. Select Spring Boot 4.1.2 for Java 21 shell and MVC, subject to CI/security review; no deployment or new production limits. Default health-only loopback and readiness false; explicit isolated test route registration. Proposed HTTP v1 contract not published.

2026-11-15: Owner accepted current foundation, requested cleaner structure later (#65), then authorized continuation. PR #64 merged 6ed88c1. Isolated runtime uses 16 KiB metadata admission ceiling and role-specific explicit HTTP wiring; not production transfer limits or ADR approval. Preserve v1 response shape and security boundaries.

2026-11-15: Owner accepted PR #66 and authorized next #30 slice. Merged 1518986. Separate administrative binding and action permission from consumer grants; require existing location authority as well. Draft policy admin HTTP contract only, no production role/policy approval or exposure.

2026-11-15: Owner accepted PR #67 and authorized next #30 slice; merged c08dd44. Backend admin proposed explicit tenantless platform permissions, exact backend action scope and global LIST/REGISTER; tenant policy admin is not platform admin. Exact server-approved targets and trusted validator required; no production role or provider policy approval inferred.

2026-11-15: Owner approved PR #68, merged 4c61e16; dependency-first instruction: skip genuinely blocked issues, fix independent engineering gaps. Proposed latest-only draft guard under #28, retain old pins and rollback via new revisions; subject to review. No live authority or deployment approval.

2026-11-15: Owner approved latest-only candidate revision guard, PR #69 merged 28f66a0 (139 tests). Continue #30 isolated API composition: explicit dependency bundle, both Flyway histories validated without migration, trusted authority readiness probe; combined stays unready pending transfer/worker. No production readiness claim.

2026-11-15: #30 final isolated contract revision: strict Jackson metadata mapping in explicit HTTP config; verify draft OpenAPI route/response/request subset against actual HTTP tests; maintain kernel Unicode ID length. No production deployment or new file limits selected.

2026-11-15: Owner approved PR #70 (a062618, 143 tests) and next #57. Proposed bounded gateway contract: opaque sensitive header, trusted atomic single-use claim, explicit exact pin/limits, provider SPI, no metadata transaction across bytes; failed attempts consume handle, reconcile/reissue separately. No production defaults or live IAM/provider policy. Review #57 design in 20_PRIVATE_TRANSFER_GATEWAY.md.

2026-11-15: PR #71 owner reviewed and merged 270fd09 (148 tests). #57 proposed first-attempt durable upload policy: explicit UPLOAD_TRANSFER grant, one hash-only random handle/session, atomic claim + required intents, no reissue before reconciliation decision; download claims denied pending verified version model. External authority revocation check-time race acknowledged; no production settings approved.

2026-11-15: PR #72 owner approved/merged 4880a42 (152 tests). #57 proposed exact canonical target/provider factory admission before credential lookup, resolver-owned adapter/credential leases; no latest fallback. Test-only S3 fixture mapping to disposable MinIO, no production routing or limits decision.

2026-11-15: Owner approved PR #73 (771ed70, 154 tests) and continued #57. Reuse native OSS wire fixture as test-only JAR, combined metadata/transfer composition shares secured facade, retain pending state and unready worker profile. No new production policy.

2026-11-15: Owner approved PR #74 (cbc61ef, 155 tests) and continuation #31. Proposed V6 first immutable expected digest and independently measured match evidence; reuse current UPLOAD_TRANSFER authorization; no available transition until scan gate, no automatic reissue, no production settings.

2026-11-15: Owner approved PR #75 (15015ec, 157 tests) and continuation. Proposed V7 scan lifecycle uses existing V3 operation ledger SCAN_UPLOAD, exact V6 digest, full-stream scan, conservative exact MIME match for isolated CLEAN, explicit dead reconciliation; no production scanner/limits/quotas or deployment selected.

2026-11-15: Owner approved PR #76 (05b6b8d, 165 tests) and continuation #31. Proposed opt-in logical handle/complete commands, 202 durable VERIFY_UPLOAD with atomic SCAN_UPLOAD handoff, explicit bounded worker tick. No production policy or deployment.

2026-11-15: Owner approved PR #77 (4f65a0d, 168 tests) and continuation. Proposed V8 bounded recovery and immutable cleanup HOLD; terminal replay current grant and original digest, 200 with logical states. Logical expiry does not prove remote write stopped; no delete/reissue without reviewed evidence.

2026-11-15: Owner approved PR #78 (edb185c, 173 tests) and #35 continuation. Proposed explicit DOWNLOAD_TRANSFER action, short-lived single-use opaque download handle, exact original clean pin and streaming digest final-chunk gate. No Range, CDN or production limits selected.

2026-11-15: Owner approved PR #79 (b8450b3, 177 tests) and continuation. Proposed V10 LIST_METADATA action, scoped keyset pagination and visible logical usage; distinct from tenant-admin/physical/quota accounting. No production policy.


2026-11-15: Owner approved PR #80, squash merged `02814b4ae0d2c596be56b798067e7b0b696c8d84`; exact-head CI 43709875026 passed 180 tests. Owner authorized #36 FS-310A1 reversible logical lifecycle only. V11 candidate in review; retention/hold, quota, physical purge, production migration and deployment remain gated. See [FS-310A1 contract](../architecture/30_LOGICAL_FILE_LIFECYCLE.md).
Implementation choice: monotonic lifecycle CAS, explicit DELETE_FILE/RESTORE_FILE, external/local scope intersection, revision-fenced download handles, clean completed files only. No retention policy or purge authority.


2026-11-15: PR #81 accepted/squash merged `660f14b84c3a6f2bbf15b7d2157189cf5d865fb4`; exact-head CI 43710854973 passed 183 tests. Owner authorized #36 FS-310A2 independent safety/recovery and conservative retention assessment only. V12 candidate; [contract](../architecture/31_LIFECYCLE_SAFETY.md). No production policy, physical purge, quota or deployment.
Engineering choice: explicit LIST_DELETED_METADATA and distinct recovery endpoint; generation-scoped optional retention evidence, unknown/expired/held blocked, even elapsed retention returns REVIEW_REQUIRED. No policy writer, purge authority or production policy.


2026-11-16 (Asia/Jakarta): PR #82 accepted/squash merged `a1fd681bd0dab49f13f14418082b9d365a2a86ee`; exact-head CI 43712395352 passed 185 tests. Owner authorized #36 FS-310A3 durable purge preparation only; V13 candidate, [contract](../architecture/32_PURGE_PREPARATION.md). Actual provider deletion, production retention/hold/quota policy and deployment remain gated.
Engineering choice: internal PREPARE_PURGE grant, immutable per-deletion-generation review ledger; exact pin and retention evidence snapshot, all reader safety UNVERIFIED, V13 executable PURGE_VERSION disabled at database and enqueue boundary. One request per generation; evidence change invalidates replay; no reapproval writer or worker.


2026-11-16 (Asia/Jakarta): PR #83 accepted/squash merged `65b6ec440cea7ec0ab30303bbdcb335c8f3952b2`; exact-head CI 43779802955 passed 187 tests. Owner authorized #36 FS-310A4 durable reader safety and simulated execution only. V14 candidate, [contract](../architecture/33_READER_SAFETY_SIMULATION.md). No actual provider deletion, production retention/hold/quota policy or deployment.
Engineering choice: atomic claim-linked reader lease, explicit successful stream/lease close acknowledgement; expired/untracked/legacy claims remain blockers. Separate bounded simulation ledger with current snapshot/drain rechecks and token/attempt fencing; no provider I/O or execution permit.


2026-11-16 (Asia/Jakarta): PR #84 accepted/squash merged `0d0cc026f90954df554a1d8fa7f99045e1ecbff0`; exact-head CI 43781101800 passed 190 tests. Owner authorized #36 FS-310A5 governed policy/reconciliation foundations. V15 candidate, [contract](../architecture/34_GOVERNED_LIFECYCLE.md); [owner decisions](11_RETENTION_QUOTA_REVIEW.md). No production policy activation, actual provider deletion or deployment.
Engineering choice: immutable draft/review, protective file hold with no release, distinct SUPERSEDE_PURGE CAS chain, independent exact-reader proof review with no production verifier. No production duration/quota defaults or activation.


2026-11-16 (Asia/Jakarta): PR #85 accepted/squash merged `8ea998faf20d0f94ac92f6a5ddb083e994cae592`; exact-head CI 43782219424 passed 193 tests. Owner authorized #36/#33 FS-310A6 isolated policy and quota primitives. V16 candidate, [contract](../architecture/35_ISOLATED_POLICY_QUOTA.md). No production activation, limits/durations, hold release, actual provider deletion or deployment.
Engineering choice: explicit isolated tenant and third actor activation of latest reviewed tenant draft; monotonic retention duration, positive explicit quota limit, conservative UNKNOWN/HELD observations, no CLEAR; pinned per-session quota ledger, serialized tenant capacity, charge until verified purge, release only unclaimed terminal upload. No runtime wiring or scoped overrides.


2026-11-16 (Asia/Jakarta): PR #86 accepted/squash merged 4af51dd819498d323345475d4a9cc3bf16d28288; exact-head CI 43785045045 passed 197 tests. Owner authorized FS-310A7 isolated transactional quota integration and reconciliation (#13/#16/#33/#35/#36). No production policy, historical backfill, hold release, actual provider deletion or deployment. PR #87 excluded.


FS-310A7 candidate PR #88: [full CI 43801908520](https://github.com/vyrnforge/file-storage/actions/runs/43801908520) passed 199 tests (zero failures/errors/skips) at implementation head `74d22283d2254d482254b19fb68e92e3c8803dcf`; V17 repeatability and two new PostgreSQL regressions. Owner review/merge pending. Final review head verification is recorded on PR #88. Engineering choices: explicit empty isolated tenant, durable tenant fence even for non-enabled initiation (contention/load evidence deferred), transaction-triggered ledger changes and read-only reconciliation. No production activation or deployment.


2026-11-16 (Asia/Jakarta): PR #88 accepted/squash merged 453cc93732bfac19e2b1dbffec3a6737af690a0e; exact-head CI 43802227997 passed 199 tests. Owner authorized FS-310A8 governed quota transitions and reconciliation safety (#13/#16/#33/#35/#36). V18 isolated candidate, independently reviewed quota limit/TTL and immutable dry-run findings. No production policy/limits, hold release, actual historical backfill, provider deletion or deployment. PR #87 excluded.


FS-310A8 candidate PR #91: [implementation CI 43803914425](https://github.com/vyrnforge/file-storage/actions/runs/43803914425) BUILD SUCCESS at `fb89dec83148525ace1c6c258e7cd3898b91358e` against main 453cc93: 203 tests, zero failures/errors/skips (58 persistence, 80 runtime, 40 domain/application/SPI/security, 13 S3/MinIO, 12 OSS). V18 migration repeatability and four new PostgreSQL regressions passed. Initial CI 43803640155 was superseded/cancelled; hardened CI 43803742192 failed V18 enablement guard dollar quoting and disposable migration rolled back; corrected full suite passed, no tests removed. Final review-head evidence recorded on PR #91. Owner review/merge pending. New initial and replacement activations require independently reviewed exact limit/TTL; new integration enablement requires reviewed activation. Historical rows retained; no actual historical backfill, production policy, hold release, provider deletion or deployment.


2026-11-16 (Asia/Jakarta): PR #91 accepted/squash merged `11c1a5ead150ec9f77ffd8225f0c070ad154bca3`; exact-head CI 43804275443 passed 203 tests. Owner authorized FS-310A9 (#13/#16/#33/#35/#36), V19 isolated dry-run manifest and restricted execution foundation. [Contract](../architecture/38_HISTORICAL_ACCOUNTING_MANIFEST.md). CI and owner review pending. No actual historical backfill, production policy/limits/durations, hold release, provider deletion or deployment. PR #87 excluded.


2026-11-16 (Asia/Jakarta): owner approved PR #92; squash merged `1419360d456079f3ffe3510babdab6eb721302f1`; [exact-head CI 43806355043](https://github.com/vyrnforge/file-storage/actions/runs/43806355043) passed 205 tests. FS-310A10 selected as next isolated increment: restricted lifecycle completion/recovery and atomic Kernel manifest command receipts. [Contract](../architecture/39_RESTRICTED_LIFECYCLE_AUDIT.md). CI and owner review pending. Production retention/limits, legal-hold release, actual historical backfill, provider deletion and deployment remain gated; PR #87 excluded.
