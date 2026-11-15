# VyrnForge File Storage

Independent, multi-tenant, multi-provider enterprise file storage service for Asset Management, ITSM and other applications.

**Status: Phase 1 — FS-100A and FS-100B foundations merged.** No deployed service or released version.

## Start here
- [Project constitution](docs/project/00_CONSTITUTION.md)
- [Current state and legacy reuse](docs/project/01_CURRENT_STATE.md)
- [Product scope](docs/project/02_SCOPE.md)
- [Architecture](docs/architecture/01_ARCHITECTURE.md)
- [Storage control plane](docs/architecture/02_STORAGE_CONTROL_PLANE.md)
- [API and integration](docs/architecture/03_INTEGRATION.md)
- [Threat model](docs/security/THREAT_MODEL.md)
- [Delivery roadmap](docs/project/03_ROADMAP.md) and [decision register](docs/project/04_DECISIONS.md)
- [Project context for AI agents](AGENTS.md) and [.gpt instructions](.gpt/GPT_PROJECT_INSTRUCTIONS.txt)

- [Phase 0 architecture review](docs/project/06_PHASE_0_ARCHITECTURE_REVIEW.md)
- [Domain and placement contract](docs/architecture/04_DOMAIN_AND_PLACEMENT_CONTRACT.md)
- [First implementation increment FS-100A](docs/project/07_FIRST_IMPLEMENTATION_INCREMENT.md)
- [Repository security evidence](docs/security/REPOSITORY_SECURITY_EVIDENCE.md)

- [Product outcomes and rules](docs/project/09_PRODUCT_OUTCOMES_AND_RULES.md)
- [FS-100B acceptance and handoff](docs/project/10_FS100B_ACCEPTANCE.md)

## Ecosystem
[Wyrmgate IAM](https://github.com/wyrmgate/iam) authenticates identities; [Platform Kernel v0.3.2](https://github.com/vyrnforge/platform-kernel/releases/tag/v0.3.2) provides shared context and reliability contracts; [VyrnForge UI](https://github.com/vyrnforge/vyrnforge-ui) provides console foundations; [VyrnRail](https://github.com/vyrnforge/vyrnrail) informs engineering governance. File Storage owns its own domain, database, storage and release.

## Layout
Backend contains the domain, provider SPI and application Maven reactor; console, SDK, contracts, deployment and tests remain reserved areas with README files. FS-100B is limited to application use cases and persistence ports; no service runtime, infrastructure or migrations. Verify with `make ci` (Java 21, Maven 3.9+, Kernel from Maven Central). See [FS-100A evidence](docs/project/08_FS100A_EVIDENCE.md). See [CONTRIBUTING.md](CONTRIBUTING.md).

Public repository: never commit secrets, private legacy source, real file content or production configuration. Licensed under [Apache-2.0](LICENSE).
