# FS-65 — quality recovery status and acceptance control

Status: **QC-00 proposed documentation change pending draft PR #115 merge; QC-01 first test-only candidate verified in separate draft PR #116, not merged or accepted**. Date: 2026-11-17 Asia/Jakarta. Canonical tested main: `a1903778af1e272ae15c025fe28c4cca369c09af`. [Owner quality issue #65](https://github.com/vyrnforge/file-storage/issues/65) is **open**, with four unchecked original criteria. This file distinguishes evidence states rather than inferring acceptance from a green check.

## Current evidence matrix

| Capability or gate | Proposed/approved | Implemented | Applied (live) | Verified evidence / outstanding |
|---|---|---|---|---|
| Modular domain, PostgreSQL metadata, reliability and opaque FileReference | Approved architecture | Yes in reviewed PR increments | No deployed service | [Main CI 44010497592](https://github.com/vyrnforge/file-storage/actions/runs/44010497592) passed 234 Java tests; not live environment acceptance |
| Provider-neutral SPI, S3 and native OSS adapter | Approved | Yes, isolated providers and fixtures | Real cloud acceptance **not verified** | MinIO/native OSS loopback tests in CI; live OSS staging and provider/credential/network verification open |
| Upload/download gateway, worker and restricted DB roles | Approved isolated foundation | Yes, isolated composition; #113 Q11-01 merged | Production deployment **not applied** | Role/claim/tenant/rollback tests in 234 suite; actual privilege/role/parameter/effective ACL audit Q11-02–05 open |
| Security fail-closed observations | Approved bounded AQ-03B | #112 merged | No production telemetry exporter/live IAM | Fixed enum counters and tests; export policy and live IAM contracts open |
| QC-01 initial test-only evidence | Owner approved QC-01 characterization/organization | [Draft #116](https://github.com/vyrnforge/file-storage/pull/116) adds two stalled-I/O characterization tests and a 133-case selected suite inventory; original 234 tests unchanged | Not applied/live; no gateway hardening | [Exact-head CI 44013961151](https://github.com/vyrnforge/file-storage/actions/runs/44013961151) and [audit 44013961276](https://github.com/vyrnforge/file-storage/actions/runs/44013961276) both SUCCESS: 236 tests, 67 SpotBugs identities unchanged, 4180/4553 lines, 2196/3297 branches; physical splits and full assertion inventory pending |
| Full backend quality/clean code | Review requested and QC-00/QC-01 approved | **Not complete** | Not applicable | [#65](https://github.com/vyrnforge/file-storage/issues/65) open, four unchecked; [#108](https://github.com/vyrnforge/file-storage/pull/108) draft; [#115](https://github.com/vyrnforge/file-storage/pull/115) review draft; 12 sampled-source findings |
| SpotBugs and JaCoCo | Measurement established | Prior combined head reports archived | Not a production security attestation | [Quality run 44009737345](https://github.com/vyrnforge/file-storage/actions/runs/44009737345): 67 SpotBugs findings; 4178/4553 lines, 2194/3297 branches. New baseline requires fresh QC-01 audit and independent triage. |
| Frontend, console, signing, release, deployment | Independently gated | Some proposals/drafts | **No** | Do not infer acceptance from implementation or library CI; release NO-GO |

## Clean-code issue resolution status

**Open and owner review required:** (1) accepted target package/responsibility map and prioritized debt; (2) reviewed behavior-preserving refactor PRs with tests; (3) capability-focused suites with case/negative-assertion preservation; (4) docs, decisions and residual risk sign-off. Reopening the issue is a governance correction, **not** evidence that these are satisfied.

## Controlled sequence and handoffs

**QC-00:** status matrix, README/AGENTS/current-state/roadmap/decision pointers and issue state corrected in draft PR #115. Scope: docs and issue metadata only. **QC-01:** [draft PR #116](https://github.com/vyrnforge/file-storage/pull/116) from unchanged production main has two exact-head verified characterization tests for transfer stalled read/write (CI 236/236), while disposable JDBC blocked statements/locks remain pending (differentiate measured risk from verified defect), and begin separating giant test suites only with a complete per-test and per-assertion inventory. Every moved test must still run; don't remove, weaken or skip any scenario. Compare tests at actual resulting PR head. **QC-02+** production deadline/connection/architecture refactors are **proposed only**, pending failure reproduction, design review and further owner approval.

Review sources: [source-linked J01–J12 register](https://github.com/vyrnforge/file-storage/pull/115), [FS-65 backend quality PR #108](https://github.com/vyrnforge/file-storage/pull/108), [master delivery #13](https://github.com/vyrnforge/file-storage/issues/13), [project architecture](../architecture/01_ARCHITECTURE.md).

## Non-negotiable gates

- Preserve existing tenant, grant and audit/atomicity checks; exact provider credential revision and historical file pin; S3 and OSS behavior; fail-closed denied boundaries; file identity; no external provider I/O inside metadata transactions.
- No production Java/SQL/schema, public API, dependency, infrastructure, IAM, signing, publishing, merge or release change without separate authorization. No arbitrary runtime plugin or cross-repository modification.
- For any QC-01 test-only PR: inventory all original 234 scenarios and their negative assertions, verify exact-head CI, assess JaCoCo/SpotBugs delta and independent code review. A passing count alone does not establish correctness, resilience or maintainability.
- Decisions record **proposed / approved / implemented / applied / verified** separately. Do not close #65 until all four original acceptance criteria and independent QA/security review have evidence.
