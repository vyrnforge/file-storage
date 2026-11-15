# Storage providers and administration control plane

Proposed. Administrators manage configured backend instances; adapters are reviewed compiled implementations, not console-uploaded executable plugins.

## Provider SPI
Define provider-neutral streaming put/get, head, delete, paginated list, optional copy, constrained upload/download authorization, multipart initiate/complete/abort, capability discovery and health. Domain receives neutral results and errors, not SDK objects. Capability matrix distinguishes required operations from optional ones. S3 adapter supports verified S3-compatible profiles, native OSS adapter preserves legacy signing/addressing behavior. No blanket compatibility claims; contract tests against each declared supported target.

## Configuration lifecycle
Draft -> test -> activate -> assign policy -> drain -> retire. Validate endpoint/TLS, capability, credential privileges, bucket restrictions, encryption, CORS/signing and controlled canary operations. Version configuration; record actor, reason, timestamp and outcome; optimistic revision checks; distribute active revisions safely across service instances. Existing sessions and objects pin locations; draining blocks new writes but permits authorized reads/completion/cleanup. Retirement blocked by live references, pending sessions/jobs or retention. Repointing physical locations is forbidden; migrate via new location, verify bytes, atomically switch locator, retain source until safe disposal.

## Dynamic vs bootstrap
Console: backend endpoint/region/addressing, locations, credential reference, tenant/app placement, quotas, file-type/size policy, retention, URL TTL, scanning policy within security bounds. Deployment: database, trust anchors, secret-manager bootstrap, network allowlists, adapter binaries, resource limits. Secrets remain in managed secret store or workload identity, never ordinary config response. Credential rotation tested with rollback; redact signed URLs.

## Console
Overview/health, Files, Backends, Locations, Policies, Tenants/Quotas, Applications, Operations, Security/Audit, Settings. Use Shared UI and IAM; distinct admin/operator/read-only scopes. Draft validation, confirmation for destructive operations, accessible status/error states, revision history and audit evidence. Backend test is a privileged, bounded network operation: deny unauthorized destinations, metadata endpoints and unsafe redirects; allow private storage endpoints only via explicit infrastructure policy. No real backend provisioning in Phase 0.

## Golden path
Register S3 and OSS; validate/activate both; upload via OSS; switch policy to S3; new upload lands on S3; old file and in-flight session still work; drain OSS; migration verifies source/destination checksum and cutover without changing file ID. Test failure, duplicate retry and rollback.

## FS-120B2A persistence implementation
See [configuration revision contract](07_CONFIGURATION_REVISIONS.md). Internal CAS activation and immutable publication implemented for review; provider validation evidence must be written by a trusted validator in FS-210B. Public administration, cache convergence, audit and retirement reference guards remain future work.

## FS-200A provider contract
[Provider and private transfer contract](10_PROVIDER_TRANSFER_CONTRACT.md) defines create-only writes, exact byte count and version targeting, stream ownership, neutral failure semantics and a shared acceptance suite. DEC-FS012 approves an independently scalable private transfer gateway, separate API/worker roles and optional private CDN; raw provider signatures are internal only, not public DTOs. Actual provider validation and capability evidence required in #25/#26/#28.

## FS-210A registry
[Registry contract](13_BACKEND_REGISTRY.md): trusted draft registration, immutable initial locations, append-only credential revision CAS, fresh DB snapshots, exact active/draining reference resolution. Actual secret manager/workload identity (#16), provider validation, network allowlist and later location admission (#28), administrator authorization and HTTP/console remain gates. No provider I/O during metadata transaction.

## FS-210B internal lifecycle
[Backend lifecycle](14_BACKEND_LIFECYCLE.md): exact draft validation boundary, exact active revision drain, V4 guarded retirement with atomic audit/event and stale-snapshot fencing. Existing pins preserved on drain. Concrete provider validation, later location admission and live network/credential policy remain pending.

## FS-220 internal policy management
[Policy control](15_POLICY_CONTROL_PLANE.md): trusted scope/location authority port, logical bounded history and active views, CAS publish and reauthorized rollback into a new revision; preserve historical placement. No public API or live authority integration.
