# FS-100A — domain foundation and placement contract

Status: READY FOR OWNER REVIEW, not authorized or implemented. Parent FS-100; prerequisite FS-000 and security gate. Goal: prove the central architectural invariant before building service infrastructure.

## Scope
Create minimal Java 21/Maven 3.9+ reactor under backend; initial domain and provider-spi modules only (domain independent; SPI depends on domain types as needed). Use package io.vyrnforge.filestorage. Pin Kernel main 0.3.0 where FileReference mapping is needed; testkit only test scope if used. Resolve GitHub Packages with approved read-only credentials or published verified artifact path; no token in repository, no private package access from untrusted forks. Document reproducible dependency resolution before requiring build CI. Pin Maven plugins and test dependencies with version/security review; no Spring Boot or PostgreSQL dependency until subsequent increments need them.

Deliver immutable IDs/value objects, tenant-bound file/version, storage backend/location/policy revision, pinned upload session, placement selection and lifecycle transition rules, neutral provider SPI and deterministic in-memory fixtures. Keep provider SDKs, HTTP, database, console and service authentication out of this increment. No legacy code import. No external storage or live secret.

## Acceptance matrix
| Test ID | Required assertion |
|---|---|
| A01 | Existing opaque file ID survives simulated physical locator migration; FileReference has no private locator |
| A02 | New policy revision changes new placements only; existing session and version retain location |
| A03 | Draft/draining/retired or unauthorized locations cannot receive new placement; ambiguous/no match fails closed |
| A04 | Draining location retains read/completion eligibility; retirement blocked by referenced objects/sessions |
| A05 | Invalid tenant/context or cross-tenant policy match rejected; no production authorization claim |
| A06 | Invalid lifecycle transition rejected; available requires verification evidence; quarantine not available |
| A07 | Credential/config revision changes cannot silently change physical identity; no raw secret fields |
| A08 | Provider port contains no vendor SDK types; optional capability unsupported explicitly |
| A09 | Kernel FileReference compatibility test with opaque ID and UUIDv7 tenant |
| A10 | Maven clean verify plus existing make ci passes; negative regression tests and dependency boundary checks |

## Implementation sequence after approval
1. Confirm license/visibility, dependency access strategy and supported toolchain. Add Maven parent + two modules and reproducible CI build in a focused PR; retain stable ci job name.
2. Add value objects and invariants with unit tests; no runtime framework.
3. Add neutral provider SPI, placement policy and lifecycle tests; use synthetic fixtures.
4. Document exact behavior, evidence, remaining gaps and update current state/roadmap. Review PR; owner controls merge.

## Explicit non-goals
No deployed service, database migrations, IAM integration, provider SDKs, real S3/OSS requests, dynamic configuration API, frontend, production scanning, migration or release. These follow FS-110/120/200/210 etc. Domain-level tests do not prove production tenant isolation, crash atomicity or provider behavior.

## Exit / next increment
All A01–A10 pass in local and hosted CI; reviewer confirms dependency direction and scope; no secrets or private data. FS-100A only proves domain and port behavior. Next FS-100B: service composition and design of persistence contracts; FS-110 trusted identity/authorization vertical slice; FS-120 durable metadata and reliability. Phase 2 then adds real adapters and console-managed backend registry.
