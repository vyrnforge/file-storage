# File Storage administration console — FS-400A foundation

Local evaluation candidate. Native-web application shell, read-only overview, file/deleted catalog, backend/location and policy views. Uses actual existing File Storage GET contracts in API mode. No provider credentials, provider URLs, browser storage tokens, writes, or local auth bypass. Backend remains authoritative; the UI probes authorized reads before presenting navigation. Policy lookup is separately authorized by the backend. No live IAM login, OAuth registration, production console or full #39 acceptance yet.

## Run locally

Requires Node.js 22+; no third-party npm dependencies. From repository root:

```sh
cd console
npm test
npm run dev
```

Open http://127.0.0.1:4173. The default is an explicitly labelled synthetic preview. Select Connect using the supplied fixture tenant; policy lookup demo / attachments. No real storage access. The local server binds only loopback and refuses writes. To evaluate with VyrnForge Shared UI, provide VF_UI_ROOT pointing to a clean locally built checkout of reviewed commit 90308f0be32e8263ac829b1909190990384ec3c3 (packages/web/dist/styles.css). This uses the actual native-web styles and tokens in local evaluation; packages remain private and source-available under VyrnForge Source License 1.0. Do not vendor, publish or redistribute them. Owner/licensor authorization and an approved package distribution channel are required before a distributable console. Preview without the build uses application layout and neutral fallback styling.

## Connect to existing backend (local read-only integration)

```sh
FS_CONSOLE_MODE=api FS_API_ORIGIN=http://127.0.0.1:8080 npm run dev
```

Supply a real authorized Wyrmgate access token issued for the approved File Storage client and requested UUIDv7 tenant in the browser. Token stays in memory only; no ID token, no provider credential, no token persistence or logs. This is a local operator integration adapter, not the production login flow. The loopback backend must already be running with the reviewed API composition and actual identity/tenant/entitlement/grant authority, not the default health-only executable. No production endpoint, database setup or infrastructure action is performed by this server. A denied request stays denied; no automatic fallback to fixtures in API mode.

A local test environment with PostgreSQL and MinIO is already exercised by backend make ci; use the disposable existing CI harness, not production credentials. The missing interactive local backend composition, approved Wyrmgate client/PKCE registration, role/permission discovery, browser integration and upload/download workflows are follow-up work. See [FS-400A contract](../docs/architecture/49_CONSOLE_FOUNDATION.md).
