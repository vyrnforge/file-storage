# Delivery roadmap and initial backlog

Approved direction 2026-11-14. Phase 1 FS-100A merged; FS-100B merged; FS-110A/B verifier merged; FS-120A authorized; FS-120B/C and later increments not authorized. IDs are stable planning identifiers, not created GitHub issues. Priorities P0 release-blocking, P1 initial enterprise scope, P2 later.

| ID | Phase | Priority | Deliverable / acceptance |
|---|---|---|---|
| FS-000 | 0 | P0 | Approve scope, ADRs, stack, legacy reuse/compatibility, deployment targets; owner sign-off |
| FS-001 | 0 | P0 | Obtain full private legacy build evidence; characterize API/tests and data migration requirements; sanitized report |
| FS-002 | 0 | P0 | Approve repo governance, license, maintainers, CI check and security reporting |
| FS-100 | 1 | P0 | Domain/placement model and provider SPI; prove stable IDs and pinned sessions |
| FS-110 | 1 | P0 | IAM/kernel context + tenant/file authorization; isolation tests |
| FS-120 | 1 | P0 | Metadata migrations + audit/idempotency/outbox; crash/retry tests |
| FS-200 | 2 | P0 | S3 and OSS adapter contract tests; supported capability matrix |
| FS-210 | 2 | P0 | Backend/location registry and secret refs; validated activation/drain/retire |
| FS-220 | 2 | P0 | Policy routing; switch new writes without changing old reads |
| FS-300 | 3 | P0 | Upload/download, batch/session/quotas, checksum/scan; legacy regression |
| FS-310 | 3 | P0 | Delete/restore/purge, recovery and bounded reconciliation |
| FS-400 | 4 | P1 | Admin console via Shared UI; config/operations/audit workflows |
| FS-500 | 5 | P0 | Production hardening: threat tests, load, backups/restore, alerts/runbooks |
| FS-600 | 6 | P1 | Legacy migration if data exists; dry run, preserve IDs, reconcile, rollback |
| FS-700 | 7 | P1 | Asset and ITSM consumer contract tests and SDK; no direct storage access |
| FS-800 | 8 | P0 | Production acceptance, independent deployment/release and owner approval |
| FS-810 | Later | P2 | Optional kernel client adapter decision based on real usage |

Security/reliability built into each phase, not deferred to Phase 5. Sequence may change after audit. No dates/estimates until requirements and team capacity established. For each task record Specified / Implemented / Applied / Verified separately, evidence links and remaining blockers. Phase 0 exit: owner approves explicit ADRs and roadmap; then create implementation issues.

## First increment refinement
FS-100A ([scope and acceptance](07_FIRST_IMPLEMENTATION_INCREMENT.md)) splits FS-100 into pure domain/placement and provider SPI foundations; FS-100B follows with service composition and persistence contract design. FS-100A sequencing refinement explicitly approved; FS-100B merged. FS-002 repository rules and CI are partly verified; administrative security readback, licensing and remaining enforcement evidence stay open.

FS-100A: PR #5 merged, hosted final CI passed, local verification outstanding. FS-100B: [outcome and rules](09_PRODUCT_OUTCOMES_AND_RULES.md), [acceptance](10_FS100B_ACCEPTANCE.md); application foundation merged (PR #6). FS-110A merged; FS-110B cryptographic verifier in review, live deployment contract blocked. FS-120 not authorized.

FS-110 is split into A (Kernel context, binding/entitlement/domain authorization and negative tests) and B (actual credential verifier, authoritative binding/grant integration, runtime endpoint wiring and environment verification). A does not close B or the overall FS-110 production gate.

FS-110A: hosted synthetic acceptance I01–I08 passed (25 total tests) in run 43611269131; merged PR #7; local verification pending. FS-110B: actual resource audience/authorized client binding and verifier integration not yet resolved. See security contract for precise source findings; FS-120 remains unstarted.

2026-11-15: FS-110A PR #7 merged; FS-110B verifier slice authorized by owner continuation. Verify real RSA signatures, dedicated audience/client profile and trusted key rotation snapshots. Live registration, JWKS transport and authoritative bindings/grants remain open; FS-110 overall production gate not closed. FS-120 not authorized.

FS-110B verifier slice implemented on PR #9; hosted CI 43665624103 passed 31 tests. Remaining FS-110B deployment/integration gates: exact intended audience/client registration, live JWKS transport/rotation, authoritative identity/bindings/grants and machine/delegation, runtime wiring and staging evidence. Do not mark overall FS-110 complete. FS-120 separate approval.

PR #8 Kernel 0.3.2 maintenance merged; PR #9 synchronized with main. Combined CI required; historical 0.3.0 evidence not a substitute.

2026-11-15: FS-120A authorized after PR #10 merge. Schema and JDBC adapter for existing initiation/read use cases, disposable PostgreSQL acceptance. FS-120B: Kernel executeIdempotent, activation CAS/concurrency and durable grant integration. FS-120C: Kernel audit/outbox, durable work/recovery. No production migrations or live IAM closure. [FS-120A contract](../architecture/05_POSTGRESQL_PERSISTENCE.md).

FS-120A code CI 43667089332: 40 tests passed, 9 real PostgreSQL tests, five modules and six readable POMs. PR #11 review pending; local verification outstanding. FS-120B/C not started.
