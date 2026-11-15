# FS-300A — Isolated policy administration HTTP boundary (#30)

Owner accepted PR #66 and authorized continuation. Baseline 151898682db583317718a080415deff74992e4b7; [CI 43692244128](https://github.com/vyrnforge/file-storage/actions/runs/43692244128) passed 129 tests, including 8 embedded HTTP, 56 PostgreSQL, 13 MinIO and 12 OSS SDK. This is a bounded #30 increment, not #39 console implementation or production permission policy.

## Responsibility and authorization

- Security owns SecuredPolicyAdmin, with distinct trusted administrative binding and exact actor/scope/action permissions POLICY_READ/POLICY_PUBLISH/POLICY_ROLLBACK. Shared AccessAuthentication preserves the existing credential profile check. Kernel pipeline checks current membership and module entitlement. No consumer grant, token role, tenant-admin default or request actor identity confers administrator access; USER only, machine/delegation remain gated. Authority outage denies.
- Runtime owns HTTP-only PolicyAdminRoutes and PolicyDtos; explicit PolicyAdminHttpConfiguration and consumer ApiHttpConfiguration share HttpBoundaryConfiguration. Both require API/COMBINED role. No component scan or import from the default health-only executable. Both use metadata admission and neutral response mapping, not file streaming.
- Persistence reuses JdbcPolicyControl and its mandatory exact scope/all-location PolicyAuthority checks (including disabled/nonwinning rules), CAS and atomic audit/event intent. No direct low-level configuration mutation from controller. RuntimeComposition composes policy service with injected trusted authority and existing DataSource. No new migration or runtime dependency. Test-only PostgreSQL Testcontainers dependency already used by persistence.
- Both administrative action authority and location authority must be present. They are checked before transaction publication; production revocation race/transaction fencing and read/denial audit policy remain owner/security gates (#16/#17–#19/#43).

## Contract

Draft [policy-admin-v1.yaml](../../contracts/openapi/policy-admin-v1.yaml). Requested tenant header + exact application/purpose query parameters; duplicate scope parameters rejected; bearer and optional correlation. No actor, credential ref, provider target or object key in DTOs. POST is metadata JSON bounded by shared 16 KiB ceiling. Policy history returns revision numbers only; detail requires every location permission. Bounded history before=0, limit default 50, maximum 100. Publish expected revision 0 initial; rollback creates new revision, must reauthorize historical rules, no pointer rewind. Responses 200 with committed revision or logical details, neutral errors; same correlation/request/no-store and transport admission as consumer routes. CAS is not admin idempotency; unknown mutation outcome requires readback, not blind retry. No provider I/O, no lifecycle/backend/location administration or console exposure in this slice.

## Evidence

Four embedded HTTP + disposable PostgreSQL tests using RuntimeComposition.migrate and real JdbcPolicyControl: policy switch/history/rollback and required atomic audit/outbox; authentication/binding/membership/entitlement/action denial; independent location and rollback authority incl nonwinning and revoked historical rules; invalid requests, role restriction and absent default executable routes. Synthetic trusted credential/authority only; real database state. Exact-head CI required. Existing 129-test baseline retained. No production DB migration, readiness, live IAM, actual provider validation or cloud operations. #16, #17–#19, #26–#28, #43 and #57 remain gates. Dedicated #65 maintainability review remains open.

