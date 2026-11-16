# FS-310A5 — Governed lifecycle

Status: owner-authorized isolated candidate; PR #84 accepted/merged 0d0cc02, CI 43781101800 passed 190 tests. #36/#16/#33. Physical purge and deployment disabled.

## Policy and hold contracts

LifecycleGovernance supplies an injected trusted authority for tenant policy draft/review and exact-file hold/reader actions. No HTTP route, runtime wiring, IAM implementation or default allow. Internal actor identity must be established by composition, not consumer-provided fields. V15 immutable tenant policy draft CAS on latest revision, explicit duration in seconds and proposed quota mode, independent reviewer. Review is REVIEWED_NOT_ACTIVE, never activation or evidence generation. No implicit duration or accounting. Production decision model (tenant/application/purpose precedence, version pin, effective dates, retroactivity, hold source and release) requires owner approval in [decision review](../project/11_RETENTION_QUOTA_REVIEW.md). Protective file-level hold assertions are append-only, across deletion generations, and independently block preparation and simulation under file lock; no release API or automatic expiry, no business authorization transfer. Existing V12 evidence remains separate and not authoritative production policy. No hold absence inference from draft or assertion absence.

## Preparation supersession

Separate SUPERSEDE_PURGE action through existing secured external/local intersection. Explicit expected predecessor ID under file lock; current-head CAS creates immutable successor for same tenant/file/version/generation with fresh current evidence and exact pin. Unique root and unique predecessor prevent forks; existing history retained, replays of same successor match snapshot and requester. Changed evidence or stale predecessor conflicts. Old simulation claim/completion rechecks successor existence under file lock. Existing physical gates unchanged. Historical simulated results remain non-authorizing; no automatic redrive.

## Reader reconciliation

Exact claimed handle hash and immutable claim (tenant/file/version/claim/expiry) plus evidence digest; requester and independent reviewer with distinct authority, trusted injected DrainVerifier confirms actual remote cessation, not expiry or mere testimony. No production verifier or discovery UI, no bulk clearance. External proof check outside transaction then re-read exact claim/request under file lock, atomic review/audit/outbox; no mutation of old reader records. Confirmed evidence is considered only by non-destructive simulation. Denied/uncertain proof remains unresolved. One immutable request per handle; replacement of incorrect evidence needs later governed workflow. No automatic cleanup; actual release would require independently approved proof standard and production roles, transport and reconciliation evidence. Direct DB privilege and TRUNCATE hardening remain deployment gates. No provider delete, production activation or quota changes.

## Verification

V15 repeatability and three PostgreSQL regressions: draft CAS, independent review and protective hold across generations; supersession grant/concurrent retry/stale claims; exact-reader review with rejected proof, distinct reviewer, transactional rollback and positive synthetic verification. Existing 190 tests retained. Exact CI evidence recorded on PR.
