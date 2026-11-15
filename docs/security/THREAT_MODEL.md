# Threat model and security acceptance

Proposed baseline; no assertion controls are implemented. Trust boundaries: external client/IAM, API/console, metadata DB, secret store, provider data plane, workers and consumer integrations.

| Threat | Required mitigation / test |
|---|---|
| Cross-tenant or file ID guessing | Authoritative tenant lookup + operation authorization; negative tests all routes |
| Forged identity/delegation | Trusted credential verification, issuer/audience/expiry, scoped service identity; deny unverified claims |
| Confused deputy | Check consumer permission and file grant; no wildcard service account |
| Signed URL abuse | Scope object/method/TTL/size; no secrets in logs; no issuance for unavailable content |
| Malicious upload | Bounded size/quotas, checksum, MIME inspection, quarantine/scan, fail closed; archive expansion limits |
| Path/overwrite abuse | Server-generated object keys, safe names/content disposition, immutable versions |
| Console SSRF | Endpoint allowlist, DNS/rebinding and redirects, TLS, network egress policy, no metadata services |
| Credential exposure | Secret references/workload identity, rotation, least privilege, no raw credentials in source or responses |
| Configuration tampering | RBAC, revision/approval, audit, validated activation and retirement guards |
| Lost/duplicate storage side effects | Durable states, idempotent jobs, fencing, bounded retries, reconciliation |
| Data loss / destructive operation | Retention/hold rules, restore, migration verification, tested backups |
| Availability / cost abuse | Rate/concurrency/size limits, quota reservations, bounded listing and job queues |

## Security gate
Run isolation/authorization negative matrix, expired/revoked identity, replay, malicious-content tests, signed upload constraints, alternate download paths, config endpoint SSRF, credentials redaction, worker crash and recovery, restore and migration tests. Verify infrastructure bucket privacy, encryption, TLS and credential scope. Capture actual test evidence; never use real secrets or files in fixtures. Private legacy source review and credential hygiene are owner follow-ups.

## FS-200A transfer threat refinement
Provider-signed instructions remain internal; redaction of toString alone is not serialization protection. Approved independent gateway transfer endpoints enforce authoritative resource/tenant/actor/app/action/session/state/expiry checks, bounded streaming and no provider redirects. See [provider transfer contract](../architecture/10_PROVIDER_TRANSFER_CONTRACT.md). Actual runtime, negative route matrix, transport deadlines, rate limits, SSRF and real provider privacy evidence remain release gates.

DEC-FS012: verify separate role identity, confused-deputy defenses, opaque handles, provider-local routing allowlist, authorization at use/resume, bounded gateway resource isolation and no API byte forwarding. Optional CDN must authorize before cache hits, isolate tenant/version, deny origin bypass, test expiry/revocation/purge; default no shared confidential caching. See #57/#58/#43.
