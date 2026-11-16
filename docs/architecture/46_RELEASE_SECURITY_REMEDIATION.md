# FS-310A17 — Release security remediation

Status: candidate; CI and owner review pending. A16 PR #103 owner approved and squash merged b9420b5d4ef507be32328040b5ea11ad3c330308, exact-head CI 43837282298: 222 tests, 126 packaged libraries and 132 formal CycloneDX components, all mapped and scanned. Seven version-matched advisories and nine missing license metadata entries; no deployment authorization. V23 unchanged. Refs #13/#16/#33/#35/#36.

## Dependency disposition

Both affected dependency paths are transitive through the reviewed Alibaba OSS adapter: aliyun-java-sdk-core 4.7.8 -> bcprov-jdk18on 1.79 and java-trace-api 0.2.11-beta -> opentelemetry-api/context 1.38.0. Parent dependency management pins org.bouncycastle:bcprov-jdk18on 1.87 and imports io.opentelemetry:opentelemetry-bom 1.68.0; OTel family alignment avoids mixing API/context/common versions. These are published artifacts ([Bouncy Castle Maven Central](https://central.sonatype.com/artifact/org.bouncycastle/bcprov-jdk18on/1.87), [OpenTelemetry Maven Central](https://central.sonatype.com/artifact/io.opentelemetry/opentelemetry-api/1.68.0)). Do not remove crypto/telemetry or rely on assumed code-path nonreachability. Native OSS SDK 3.18.4, existing S3/OSS provider SPI, lifecycle/tenant isolation, Kernel v0.3.2 and migrations remain unchanged. No ecosystem repository changes.

| A16 advisory | A16 finding | Remediation |
|---|---|---|
| GHSA-574f-3g2m-x479 / CVE-2025-14813 | GOST keystream reuse | bcprov 1.87 |
| GHSA-9pwp-9qqc-pr26 / CVE-2026-8763 | Name constraints bypass | bcprov 1.87 |
| GHSA-c3fc-8qff-9hwx / CVE-2026-0636 | LDAP injection | bcprov 1.87 |
| GHSA-p93r-85wp-75v3 / CVE-2026-5598 | Timing channel | bcprov 1.87 |
| GHSA-qp49-qgx5-5m26 / CVE-2026-13506 | ASN.1 depth guard | bcprov 1.87 |
| GHSA-ccrj-gw8r-854m / CVE-2026-118259 | Quadratic tracestate parsing | OTel 1.68.0 |
| GHSA-rcgg-9c38-7xpx / CVE-2026-45292 | Unbounded baggage allocation | OTel 1.68.0 |

Version matches are not proof of reachability; selected versions exceed the recorded fixed thresholds (BC 1.85 for the two latest A16 BC advisories, OTel 1.67.0 for tracestate). No accepted vulnerability exceptions. Fresh OSV result is required. Regression coverage tests actual classpath artifact versions, SHA-256 and standard RSA sign/verify interoperability, W3C trace/baggage extraction and invalid trace rejection; retain full existing provider contract, Wyrmgate, persistence/runtime suites.

## Evidence and security gate

CI builds exact candidate, generates CycloneDX 1.6, verifies every packaged library's digest, matches formal graph, runs pinned OSV container offline against a fresh public Maven database with no network and no private data sent to scanner API. New security regression gate verifies exact patched PURLs and family alignment, full formal BOM and packaged scan coverage, raw report and evidence digests, no baseline or new advisory, NO-GO release state. Fails on scan unavailable or incomplete; retain evidence before gate. Five negative/positive self-tests cover clean, unavailable, regression/partial scan, new/baseline finding, improper release decision. No finding suppression or severity threshold. Zero reported advisories means this scan only, not CVE-free, no shaded/native/OS/base-image coverage or external penetration test. Maven checksums and provider compatibility tests are not independent supply-chain authenticity proof.

## License and notice evidence

Parent POM declares Apache-2.0 matching repository LICENSE; inherited metadata addresses first-party modules. Do not fabricate third-party Maven license metadata. Collect original license/notice/copyright/third-party texts embedded in exact nested JARs and repository LICENSE into bounded notice archive with component and document hashes. For the two historical artifacts missing POM metadata (Alibaba KMS 2.11.0, RAM 3.1.0), fetch only exact public Maven source archives and compare published SHA-1 checksum; record SHA-256, source file Apache-2.0 header coverage and actual notices, corroborating upstream [Apache-2.0 project declaration](https://github.com/aliyun/aliyun-openapi-java-sdk#license). Missing/unavailable/mixed source evidence remains REVIEW_REQUIRED; do not rewrite the upstream BOM or treat source attribution as legal release approval. License evidence tied to actual runtime/BOM and nested library digests. Review multi-license alternatives (CDDL/GPL with classpath exception, EPL/LGPL, EPL/GPL with classpath exception, BSD/Apache), Bouncy Castle license, JDOM/Plexus custom labels, required notices/copyright and any source-distribution obligations; preserve actual license text. No blanket third-party distribution clearance. Review bundled/shaded content and build/runtime OS packages separately.

## Signed provenance preparation

Manual-only workflow `.github/workflows/staging-attestation.yml` requires owner-approved exact main SHA and approval reference; no PR execution, no automatic dispatch. Read-only build job rechecks full build/scan/license evidence and stores candidate bytes as short-lived internal artifact. Separate privileged job downloads same-run candidate, verifies SHA/source/run/evidence bindings, then pinned GitHub attest action creates signed build provenance and CycloneDX SBOM attestations. Verify exact certificate workflow identity, repository, main source ref and source digest via GitHub CLI; no self-hosted runners. GitHub attestation permissions isolated to signing job; no package/release publishing or deployment. Use reviewed patched GitHub CLI (>=2.102.0; exact cert identity avoids signer-workflow prefix ambiguity). Actual attestation requires owner-approved dispatch, supported repository plan/permissions and approved artifact review; this workflow is prepared, not executed. No signed artifact claim; CI evidence remains unsigned, and attestation itself is not a release/security/deployment approval.

## Admission

Staging and release NO-GO until final scan, license/notice dispositions, signed provenance verification, concrete approved non-production environment and real external integrations, ACL/TLS/network/monitoring and recovery evidence, separate deployment decision. No provider deletion, hold release, historical backfill, production policy/limits, provisioning, deployment or publication. PR #87 excluded.
