# Domain and placement invariants

Approved direction for FS-100A; implementation reviewed against acceptance tests. No database or public API schema is defined here.

## Identity and ownership
FileId is a nonblank opaque string, never derived from bucket/key; preserve legacy IDs. Tenant ownership is mandatory for tenant-owned files; use kernel-compatible UUIDv7 tenant IDs. FileVersionId, StorageLocationId and UploadSessionId are independent identities; new platform-owned IDs may use UUIDv7. FileReference maps logical ID, tenant and logical service identifier only plus allowed snapshots; never expose locators. FileVersion content is immutable after verification; metadata changes are separate.

## Storage placement
Backend = adapter kind + endpoint/region/addressing + credential reference and revisions. Location = immutable physical identity (backend/physical namespace); do not mutate bucket/prefix or backend physical target while referenced. Policy revision = immutable rules selecting an eligible active location for tenant, authorized consumer and purpose. Resolution uses exact match/explicit ordered precedence; ambiguous or no allowed match fails closed. No default fallthrough across tenants. New session pins location and policy/config revision, not raw secrets. Credential rotation may use an authorized newer credential revision for the same physical target; no change in identity. Persisted locator contains internal location ID, object key and optional provider version; never serialize in consumer API.

Backend status: draft, active, draining, retired. Draft/retired not eligible for new placement. Draining disallows new placements but permits authorized completion/read/cleanup. Retirement requires zero live references, jobs and sessions; production persistence enforces this atomically. Activation requires validation of exact config revision; edits invalidate prior validation.

## Lifecycle rules
FileVersion: pending -> verifying -> available or quarantined/failed. Available is only permitted after size/digest and required scan approval. File: active -> soft deleted -> restored or purge pending -> purged; retention and hold checks gate purge. No file availability via alternate download path. UploadSession: initiated -> uploading -> verifying -> completed or failed; cancel/expiry release reservation exactly once via later persistence. Duplicate completion must be idempotent and concurrency-safe in later service layer. First increment models allowed transitions only; no claim of durable quota, scanning, authorization or recovery.

## Provider SPI minimum
Neutral values: object locator, metadata, stream, paginated list cursor, capability flags, neutral errors. Required put/get/head/delete/list; optional presign/multipart/copy capabilities negotiated explicitly. No SDK imports in domain. Port methods document streaming ownership/close, timeout/cancellation and failure semantics; no fake capability claims. First increment defines interface and deterministic test doubles only, not live providers. Avoid a generic provider configuration map containing untyped secrets.

## Concurrency and migration (later)
Stage external bytes outside DB transaction, verify and commit metadata + audit/event/idempotency result atomically when JDBC chosen. Reconcile uncertain outcomes; use fencing and bounded retries. Migrate by copy, verify, atomic locator switch, source retention and eventual disposal. Logical ID/version stays stable. Reader leases or signed URL expiry govern safe source removal. Production repository constraints and transaction design reviewed in FS-120.
