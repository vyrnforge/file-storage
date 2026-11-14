# FS-100B application foundation — acceptance and handoff

Status: implementation review. Owner directed next increment after FS-100A merged. Source main 2effe678943242260c64ef252bae7378aa61434a, [product rules](09_PRODUCT_OUTCOMES_AND_RULES.md). Product owner accepts results; Technical Delivery Lead reviews transaction contracts; Backend & Domain Engineering implements; Security & IAM owns later trusted context adapter; QA runs matrix.

## Observable acceptance
| ID | Given | When | Then |
|---|---|---|---|
| B01 | Authorized tenant A / Asset, OSS policy | Initiate synthetic attachment | FileReference, session ID, pending status; exactly one complete aggregate, pinned placement |
| B02 | Existing OSS session; policy switched to S3 | Initiate new request and read previous | New S3, original OSS; views expose neither |
| B03 | Missing/ambiguous/ineligible placement | Initiate | No writes; neutral placement failure |
| B04 | Tenant B or denied consumer/actor | Read known file/session or initiate | No disclosure or mutation; lookup tenant-bound; metadata-only grant cannot read session or replay initiation |
| B05 | Same caller/request key | Replay same command, then changed command | Same IDs, no new placement; changed request conflicts; revoked access denied |
| B06 | Open session expires exactly now | Read | Effective EXPIRED, no writes, no grant |
| B07 | Repository transaction rejects insertion | Initiate | No partial file/version/session; retry with same key after rollback succeeds |
| B08 | Domain/provider modules built | Run make ci | All prior tests and new tests pass, dependency rules maintained |

## Engineering contract
Application module depends on domain/kernel only; provider SPI used later for external bytes. Transaction callback is synchronous and atomic: reads config+request+aggregate consistently, creates unique tenant/file/session/version and request identity as one unit, rollback on exceptions; no external side effects inside. Production implementation needs database constraints, isolation/CAS, durable Kernel audit/events/idempotency and recovery in FS-120. Test fixture demonstrates rollback only, not production durability. Auth port receives internally resolved actor context; never construct from untrusted HTTP fields. No real IAM or allow-all implementation.

## Exclusions / gates
No live upload/download, completion, quota, malware scanner, admin API, database, provider SDK, UI, deployment, public API or release. Local verification unavailable (Maven absent/DNS failure); hosted CI evidence required, local gate remains open. No later increment authorized. Next FS-110 trusted IAM/authorization, then FS-120 persistence and reliable mutations.

## Hosted verification
[Run 43610564780](https://github.com/vyrnforge/file-storage/actions/runs/43610564780) at be6571f883df0942792bbe3653c4231586c8c2d3 passed: 8 domain + 2 SPI + 8 application tests (18 total), zero failures/errors/skips; Kernel checksum, hygiene, dependency boundary and Maven reactor success. PR #6 clean at evidence capture. B01–B08 hosted verified at synthetic application level. Local full build remains outstanding; no production security, durable persistence or real provider evidence. No merge performed.
