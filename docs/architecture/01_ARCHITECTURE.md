# Architecture and domain boundaries

Approved architecture direction; FS-200C implementation merged; FS-210A registry authorized. DEC-FS012 approved: one modular product with independently deployable API, transfer gateway and worker roles; combined development profile permitted. Java 21 / Maven / Spring Boot / PostgreSQL / Flyway approved; exact framework/database versions selected in relevant later increments.

 ```mermaid
flowchart TD
  IAM[Trusted IAM] --> API[File Storage API / control plane]
  APPS[Asset / ITSM / consumers] --> API
  APPS --> TRANSFER[Private transfer gateway]
  UI[Admin console / Shared UI] --> API
  API --> DOMAIN[Shared file domain / application]
  TRANSFER --> DOMAIN
  WORKER[Durable worker role] --> DOMAIN
  DOMAIN --> META[(Service metadata and reliability DB)]
  DOMAIN --> K[Kernel 0.3.2 library]
  TRANSFER --> ROUTER[Trusted placement and provider SPI]
  WORKER --> ROUTER
  ROUTER --> S3[S3 adapter]
  ROUTER --> OSS[Alibaba OSS adapter]
  S3 --> B1[(Private S3 locations)]
  OSS --> B2[(Private OSS locations)]
  APPS -. optional authorized downloads .-> CDN[Private CDN / edge]
  CDN -. private origin .-> TRANSFER
```

## Boundaries
API authenticates and establishes context; domain enforces access and lifecycle; control plane governs backends/policies; persistence owns transactional metadata; provider adapters own SDKs and signing; worker reconciles asynchronous side effects. Kernel is a library, not a remote storage service. Consumer SDK speaks File Storage API; no direct bucket/database access.

## Conceptual model
- File: stable opaque ID, authoritative tenant, owner/application, logical name, state, current version.
- FileVersion: immutable content identity, verified size/digest/MIME, timestamps.
- StoredObject: version ID, location ID, physical key, provider version/generation if applicable, verification state. Multiple copies possible later.
- StorageBackend: adapter kind, connection parameters, credential reference, revisions/status.
- StorageLocation: stable backend + bucket/container/namespace; physical identity immutable once referenced.
- StoragePolicy: tenant/app/purpose rules and revision; select authorized location deterministically, fail closed if no match.
- UploadSession: actor/tenant, reserved quota, file/version, pinned location and policy/config revision, expiration and status.
- Operation/job and audit/event intents: durable recovery and accountability.

Logical file ID never changes on migration. Old location remains readable until verified cutover. Credential rotation may change connection revision without changing physical identity. Never silently repoint a referenced location to a different bucket. No automatic cross-tenant deduplication.

## Lifecycle and consistency
Initiate -> uploading -> verifying/scanning -> available or quarantined/failed. Expired/cancelled sessions release reservations and clean staged objects. Available -> soft deleted -> restored or purge pending -> purged only after retention/hold checks and verified deletion. Use optimistic concurrency or locked transitions, durable job leases/fencing, unique object keys and idempotent commands. Object store and DB do not share a transaction: stage, verify, commit metadata + required intents, reconcile uncertain effects. Never advertise availability before required verification.

## Deployment
One product release with separately scalable API, transfer and worker runtime roles, plus console build. Combined profile permitted; separated API does not carry file bytes. Gateway and worker roles use restricted identities and shared authoritative metadata; optional private CDN requires approved cache policy and evidence. Service-owned metadata database, backend connections and secrets. No live credentials or deployment targets yet. Configuration control plane must converge revisions across instances and preserve existing reads during drain. Health distinguishes process liveness, readiness and per-backend degradation.

FS-120C2 internal work ledger and Kernel outbox dispatcher: [recovery contract](09_DURABLE_RECOVERY.md). No external side effects in DB transactions; actual worker deployment and provider handlers later.

FS-200A: [provider and private transfer contract](10_PROVIDER_TRANSFER_CONTRACT.md). Preserve service ownership of authorization, bytes and physical location. Approved runtime topology is specified only; no deployed gateway or provider adapter yet.

FS-210A: [registry contract](13_BACKEND_REGISTRY.md); internal commands and exact pinned credential resolution, no deployed control plane or secret manager.
