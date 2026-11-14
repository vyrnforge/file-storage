# Delivery roadmap and initial backlog

Approved direction 2026-11-14. Phase 1 FS-100A only authorized; later increments not authorized. IDs are stable planning identifiers, not created GitHub issues. Priorities P0 release-blocking, P1 initial enterprise scope, P2 later.

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
FS-100A ([scope and acceptance](07_FIRST_IMPLEMENTATION_INCREMENT.md)) splits FS-100 into pure domain/placement and provider SPI foundations; FS-100B follows with service composition and persistence contract design. FS-100A sequencing refinement explicitly approved; FS-100B remains future work. FS-002 repository rules and CI are partly verified; administrative security readback, licensing and remaining enforcement evidence stay open.
