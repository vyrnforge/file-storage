# FS-110A — trusted identity and authorization boundary

Status: implementation review; not a deployed authentication service. Source: File Storage main 0f08b3054c6835c8f40913c38ca09c8d359d6ce1, approved DEC-FS006/009, BR-01/04/05, Kernel v0.3.0 [identity boundary](https://github.com/vyrnforge/platform-kernel/blob/v0.3.0/docs/integration/identity-boundary.md), Wyrmgate [SSO security boundary](https://github.com/wyrmgate/iam/blob/main/docs/security/identity-provider-sso.md) and ADR-0042. Owner reviewed previous increment and directed next.

## Source findings and limits
Wyrmgate current security document describes discovery/JWKS/auth-code PKCE, opaque public sub derived from tenant and identity, minimal claims, no administrative authority from tokens; client credentials/token exchange/introspection are deferred. ADR-0042 broader direction is not proof of those runtime extensions. Kernel ValidatedIdentityAdapter is NOT a JWT verifier. No assumed tenant, roles, client-id or app claims. Do not use ID tokens as access tokens. Live issuer/audience/resource access-token profile, exact server-side binding authority and machine identity remain inputs for FS-110B; no production default, no external IAM changes.

## Additional source verification
Pinned Wyrmgate main ea4a711394aadabe513df05cd606b4c1e8550f96, [IdpJwtIssuer.java](https://github.com/wyrmgate/iam/blob/ea4a711394aadabe513df05cd606b4c1e8550f96/apps/server/src/main/java/io/wyrmgate/iam/idp/protocol/IdpJwtIssuer.java): access JWT header typ=at+jwt, ID token typ=JWT; both aud=registered clientId; access claims iss/sub/aud/iat/exp/client_id/scope; default lifetime five minutes. No separate File Storage resource audience claim or delegated actor claim is established by this source. A token issued for an arbitrary consuming application's login is not automatically valid for File Storage. FS-110B must agree on intended File Storage resource audience and authorized client binding, validate access-token type and client_id/audience relationship, and avoid token confusion. Current single configured trust issuer/audience is an isolated boundary, not a production multi-client or service-to-service solution. No IAM repository modifications.

## Boundary and business rules
1. Trusted verifier validates signature, accepted algorithm/key, issuer, audience, access-token profile and time validity; returns minimal VerifiedAccess. FS-110A accepts a verifier port, not raw token decoding; rechecks configured issuer/audience, type, expiry/not-before. Token never stored or logged. Failure mapped to neutral authentication error.
2. Exact (issuer, subject, configured audience) server-side binding returns canonical globally unique actor, tenant and consumer, permitted endpoint actions. Requested tenant is only selector; must match binding. Only USER initially; service/system identities and delegation fail closed until approved contracts. No token projection grants file permissions.
3. Kernel ValidatedIdentityAdapter / RequestContextPipeline checks membership and entitlement; entitlement future-effective fails closed. Endpoint hook enforces binding action and initiation grant. Actual file resource authorization occurs after tenant-scoped lookup, including replay. Fresh request ID and preserved correlation via Kernel.
4. Grant is exact actor+tenant+consumer+action+purpose; initiation uses purpose grant; owned-read grant requires exact owner actor AND consumer; explicit file grant requires exact file ID, permits cross-app read only if all other gates pass. No wildcard or tenant-admin override. Grant validity [notBefore, expiresAt), revoked denied. Missing/unavailable source fails closed. Metadata and session are separate permissions. Replay rechecks both initiate and session rights. No grant administration API or automatic grant provisioning; grant store is trusted port, test fixture only. Owner policy for grant issuance/delegation/administrator override remains open.
5. No consumer-facing DTO contains credentials, provider locators or privileged context. Application Caller is internal; future HTTP only invokes secured facade, never direct application method. No signed URLs or bytes.

## Acceptance
| ID | Scenario / observable result |
|---|---|
| I01 | Valid access projection, exact binding, membership, entitlement, purpose grant -> initiate; canonical tenant/consumer/actor, fresh request correlation |
| I02 | Invalid/missing token, wrong issuer/audience/type, expired/not-yet-valid -> denied before writes |
| I03 | Missing binding, wrong tenant, service/system actor, disallowed endpoint -> denied |
| I04 | Membership revoked or entitlement disabled/mismatched/future -> denied |
| I05 | Same tenant/app but different actor cannot read owned file; same tenant other app requires exact file grant |
| I06 | Metadata-only grant cannot read session; expired/revoked grants fail; replay reauthorized |
| I07 | Grant store failure fails closed; no provider/credential data in error |
| I08 | All prior tests + security tests + dependency boundary + Kernel checksum pass hosted CI |

## Handoff and blockers
FS-110A tests use synthetic verifier, binding, membership/entitlement/grant and transaction fixtures; no JWT cryptography, JWKS rotation, live IAM, database or HTTP validation. FS-110B requires registered File Storage resource audience/access-token profile and issuer, trusted verifier implementation/config, canonical tenant mapping, authoritative binding/grant source, user vs machine/delegation contract, real negative token tests and staging evidence. Owner and IAM integration team decide; do not infer client credentials or trust admin roles. FS-120 durable metadata/grants/idempotency separate. No production readiness or later authorization. Local full build remains pending; CI required.

## Hosted evidence
[CI 43611269131](https://github.com/vyrnforge/file-storage/actions/runs/43611269131), code commit 5aa7ecb32f0e708accef9c9177060e728f1ab43c: verified logs, 25 tests (8 domain + 2 SPI + 8 application + 7 security), zero failures/errors/skips, all four Maven modules BUILD SUCCESS, dependency boundaries, Kernel checksum and hygiene passed. I01–I08 hosted synthetic acceptance verified; no actual JWT signature/JWKS or live identity authority proof. Local full build remains pending. PR #7 open; owner review/merge required. FS-110B blocked on above integration contract.

## Subsequent merge and verifier slice
PR #7 merged at 69d0ef978a5f04c283331851c00368632639de5c; final CI [43611373559](https://github.com/vyrnforge/file-storage/actions/runs/43611373559) succeeded. FS-110B implements concrete cryptographic verification using trusted JWKS snapshots; live IAM integration remains open. See [verifier contract](FS110B_JWT_VERIFIER.md).
