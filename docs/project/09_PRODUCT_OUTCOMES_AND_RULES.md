# Product outcome and business rules

Source: approved DEC-FS001–010, scope and roadmap; legacy capability inventory; main 2effe678943242260c64ef252bae7378aa61434a. Owner directs continuation using Product & Business Analysis. Product owner owns priorities/policy exceptions. FS-100B is an internal application foundation, not a usable deployed product.

## Outcome brief
Actor: authorized Asset/ITSM consumer acting for a tenant. Trigger: attach a file to a business record. Desired outcome: initiate storage, retain logical FileReference, observe status and retrieve authorized metadata without a provider decision. Administrator: change default placement without breaking existing sessions or files. Legacy observation: single configured OSS bucket and object keys; no persisted backend identity. Hypothesis: configuration-driven placement reduces consumer deployment changes and migration disruptions. Alternative: patch OSS-only config; cannot satisfy multi-backend identity and switching requirements.

| Outcome measure | Denominator / observation window | Baseline / collection |
|---|---|---|
| Backend change requires zero consumer deployments | Each approved policy change in a staging acceptance run; later 30-day operations window | Unknown legacy baseline; record consumer deployment IDs and policy revisions |
| Existing file/session continuity | All pre-change file references and open sessions in switch scenario | Unknown; synthetic integration first, staging inventory later |
| Authorized initiation success | All valid requests in staging test window | Unknown; log outcome code/correlation only, no file names/locators |
| Unauthorized disclosure | All negative tenant/app/grant test cases | Target zero; synthetic negative matrix; production evidence later |

Targets above are acceptance hypotheses, not SLOs or measured production performance. FS-100B tests metadata orchestration only.

## Business rules (approved direction, concrete FS-100B interpretation)
| Rule | Conditions / precedence | Outcome |
|---|---|---|
| BR-01 | Internal trusted actor context missing or invalid; authorization port denies | Fail closed; no storage record |
| BR-02 | Tenant/consumer from context, purpose from request; explicit exact match and eligible location | Resolve current policy; no cross-tenant fallback; ambiguous/missing/inactive/unauthorized denies |
| BR-03 | Initiation accepted | Atomically create file, pending version, initiated session, pinned location/key/revisions and request identity; no bytes yet |
| BR-04 | Same actor+tenant+consumer+request key and same request fields | Reauthorize; return original initiation identity/status, not a fresh placement; different request conflicts |
| BR-05 | Metadata/session read | Tenant-scoped lookup AND resource authorization; missing/denied indistinguishable; no location, key or credentials in view |
| BR-06 | Policy switch after initiation | New request resolves new policy; old session and locator unchanged; replay does not re-route |
| BR-07 | Expired initiation/session | Read remains truthful; report expiry effective at now >= expiry for open session; no new grant or implicit mutation |
| BR-08 | File/version unavailable | Metadata may show pending state to authorized caller; no content access, signed URL or availability claim |

Request idempotency in FS-100B is an internal transaction-port contract and test fixture, not durable Kernel idempotency. Session TTL injected by composition; no production default. Consumer ownership is recorded with resource and grant port decides access; no assumption that same tenant implies permission. Missing config fails closed. No business record association stored by File Storage.

## Owner decisions before affected scope
IAM audience/delegation and cross-app sharing (counterexample: ITSM wants Asset file in same tenant) -> owner + Security/IAM FS-110; deny unless explicit grant. Quota/retention/TTL defaults and scan policy -> owner before FS-300/310; no production defaults. Completion retry/expiry race and durable reservation -> FS-120/300; no completion command yet. Signed transfer delivery exposure and provider version semantics -> FS-200. Full legacy build/data compatibility -> owner/private audit before FS-600. No inferred policy exception.
