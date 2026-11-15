# API, events and platform integration

Proposed contracts, not published API. Use [Kernel v0.3.2](https://github.com/vyrnforge/platform-kernel/tree/v0.3.2), [identity boundary](https://github.com/vyrnforge/platform-kernel/blob/v0.3.2/docs/integration/identity-boundary.md), [reliability semantics](https://github.com/vyrnforge/platform-kernel/blob/v0.3.2/docs/contracts/reliability-semantics.md), [three-JAR distribution](https://github.com/vyrnforge/platform-kernel/blob/v0.3.2/docs/integration/three-jar-distribution.md).

## Kernel adoption
Maven io.github.vyrnforge:platform-kernel:0.3.2; optional platform-kernel-jdbc:0.3.2 if PostgreSQL chosen; platform-kernel-testkit test scope. Java 21 baseline; wire schemas remain v0.2. Resolve all kernel artifacts from Maven Central; no local GitHub-release bootstrap, custom repository or credential. Do not mix old/new coordinates or versions. IAM verifies credentials and emits trusted ValidatedIdentityCredential; never decode unsigned token claims into context. Resolve membership, entitlement and domain authorization separately. Authoritative tenant-scoped lookup on every operation, including replay, worker and signed URL issuance. Resource access not implied by FileReference.

FileReference: opaque fileId, ownerTenantId (UUIDv7 if present), logical storageService, optional name/mimeType/size/createdAt snapshots. Never expose bucket/key, signed URL or provider secrets as reference. Tenant-owned file requires tenant. Stable ID across physical migration.

## API design
Versioned data-plane API: initiate upload, batch/status/refresh/progress/cancel, complete, get/list metadata, authorize download, delete/restore. Versioned control-plane API: backend draft/validate/activate/drain/retire, location, policy, quotas, operations/audit. Exact routes, DTOs, HTTP status semantics and compatibility mapping TBD in OpenAPI before implementation. Use cursor/page bounds, safe error codes, correlation/request headers and explicit idempotency. Direct upload/download grants are short-lived and scoped; authorization on issuance; explain revocation limits.

## Reliability
Use tenant+operation+idempotency key and effective request fingerprint (including caller/target semantics). Reauthorize replay, do not replay expired signed URLs. If JDBC selected, executeIdempotent atomically commits metadata + required audit/event intents + result using supplied connection; no object-store calls inside transaction. Outbox at least once; consumers dedup eventId; retries/dead-letter reconciliation, no exactly-once claim. Events proposed: file.created, file.available, file.quarantined, file.deleted, file.restored, file.purged; version payloads; no secrets or unnecessary file names. Audit authorization-sensitive reads and mutations, admin changes and failures as policy requires.

## Consumer boundary
Asset/ITSM own business association and permissions; File Storage validates tenant, caller and file grant. Use narrowly scoped delegation or explicit grants, not unrestricted application credentials. Detach does not automatically purge; retention/other references honored. Client library lives in File Storage initially; optional kernel adapter after proven consumer use, separately approved/versioned. Kernel core must not import service or provider SDK. No kernel changes in Phase 0.

## Observability
Preserve business correlation; fresh request ID for each invocation; trace metadata opaque. Avoid raw tokens, filenames, URLs and high-cardinality tenant dimensions by default. Separate liveness/readiness. Define metrics and SLOs after workload evidence.
