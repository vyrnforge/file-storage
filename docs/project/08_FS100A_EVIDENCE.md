# FS-100A implementation evidence

Owner authorization: 2026-11-14, DEC-FS001–010, public Apache-2.0, recommended stack and roadmap. Scope: pure domain and provider SPI only. No service runtime or deployment.

Implemented on review branch: Java 21 Maven reactor, immutable domain values, tenant-bound placement, pinned session location/key/revisions, backend validation/drain/retire, file/version/upload transitions, stable logical identity and actual Kernel FileReference mapping; neutral port with synthetic memory fixture. Exact policy scope and explicit numeric priority, no implicit fallback. Provider presign/multipart contracts deferred to FS-200 security design; capability flags reserved, no live implementation. Production authorization, durable configuration CAS, quota, scan evidence, persistence and migration side effects remain later work.

Build: public Kernel v0.3.0 release JAR verified against SHA-256 from release metadata, installed with Maven install-file; no credentials, no vendored code. Java/Maven plugins and JUnit aligned with Kernel where relevant. CI retains required ci name. Local environment lacks Maven and cannot resolve GitHub; hosted CI is verification path. Record actual run after execution; do not mark all acceptance tests verified until evidence exists.

Acceptance mapping: StorageModelTest A01–A07/A09; StorageProviderTest A08; A10 make ci with Maven clean verify, dependency boundary check by source/import review. No live provider or production security test. Review and merge require owner action. Further increments not authorized.
