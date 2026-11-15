# Project constitution

Status: architecture direction approved 2026-11-14; Phase 2 through FS-120C2 merged (PR #55); later work requires owner authorization.

## Mission
Provide a standalone enterprise storage service that abstracts storage-provider details for authorized applications and administrators without losing durable file identity or operational control.

## Non-negotiable boundaries
- File Storage owns bytes, metadata, file lifecycle, access enforcement and placement. Consumer products own business records and business-resource permissions.
- IAM owns credential verification and identity; kernel owns reusable contracts and controls, not storage. Shared UI owns design primitives.
- Preserve existing behavior where useful; replace coupling, not functionality for its own sake.
- Independent releases and deployability; no shared product database or direct consumer bucket access.
- Tenant isolation, least privilege, private storage by default, audited administrative changes, no secret material in source.
- Configuration is versioned data, not executable code. Existing files and sessions retain their physical placement when defaults change.
- No runtime implementation before approval of scope, decisions and roadmap. No claim of production readiness without acceptance evidence.

## Engineering method
Characterize legacy behavior; identify evidence; propose decisions; implement bounded increments; verify negative paths and recovery; record results. Prefer one modular product with clear internal interfaces. DEC-FS012 approves independent API, transfer and worker runtime roles without separate business ownership; combined deployment permitted. Keep the initial architecture minimal; introduce abstractions only for proven variation. Public-facing contracts version separately from artifacts; backward-compatible changes preferred.

## Governance
Owner approves ADRs, scope expansion, technology selection, production releases and migration/cutover. Track Specified / Implemented / Applied / Verified separately. Changes to shared ecosystem repos require separate approval. Repository PR approval does not automatically approve every proposed architecture decision.

2026-11-15: FS-120A merged; FS-120B1 transactional initiation in scope. FS-120B2/C and production work require review.

2026-11-15: Master delivery tracker [#13](https://github.com/vyrnforge/file-storage/issues/13) created with 37 work packages. FS-120B2A (#20) is the next candidate, not authorization. Planning preserves approved scope and production gates.
