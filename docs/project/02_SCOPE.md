# Product scope and capability map

Approved direction, 2026-11-14; individual implementation increments require authorization. Primary consumers: Asset Management, ITSM, other tenant-aware applications; administrators operate storage via a first-class console.

| Capability | Initial enterprise release | Later/conditional |
|---|---|---|
| File data plane | Upload/download, metadata, listing, batch/session, quota, delete/restore, integrity | previews, advanced search |
| Storage providers | S3 and native Alibaba OSS; tested S3-compatible profiles | Azure/GCS/other adapters |
| Control plane | Backend/location registry, credential references, validation, activation, policy assignment, drain/retire, audit | automated tiering, replication |
| Lifecycle | explicit pending/available/quarantined/deleted/purged, bounded cleanup and reconciliation | advanced retention/legal hold when required |
| Security | verified IAM, tenant/file/application authorization, private buckets, scanning/quarantine for untrusted content, opaque service-controlled transfer handles | customer-managed keys, regulatory residency controls |
| Integration | stable FileReference, versioned API/events, consumer SDK | optional kernel integration artifact after real consumers |
| Operations | independent API/transfer/worker deployment roles, health, metrics, backups/restore, migration runbooks | optional private CDN, multi-region active-active |

Out of scope: object-storage server implementation, filesystem protocol, IAM replacement, generic BPM, arbitrary runtime plugins, consumer business workflows, shared consumer database, direct consumer bucket management.

## Release boundary
Support configuring instances of already-installed adapters without redeploying consumer apps. New adapter/protocol support requires reviewed code and release. S3-compatible is not a universal compatibility guarantee. Do not promise all providers support every optional capability. Exact initial limits, versioning policy, retention defaults and target workloads are pending owner decisions.
