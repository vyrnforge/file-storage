#!/usr/bin/env python3
"""A17 security regression gate. No deployment or release authorization."""
import hashlib
import json
import pathlib
import sys
import unittest

EXPECTED = {"org.bouncycastle:bcprov-jdk18on": "1.87", "io.opentelemetry:opentelemetry-api": "1.68.0",
            "io.opentelemetry:opentelemetry-context": "1.68.0"}
BASELINE = {"GHSA-574f-3g2m-x479", "GHSA-9pwp-9qqc-pr26", "GHSA-c3fc-8qff-9hwx", "GHSA-p93r-85wp-75v3",
            "GHSA-qp49-qgx5-5m26", "GHSA-ccrj-gw8r-854m", "GHSA-rcgg-9c38-7xpx"}
def evaluate(review, meta, bom, scan):
    errors = []
    components = bom.get("components", [])
    resolved = {}
    for c in components:
        resolved.setdefault(f"{c.get('group')}:{c.get('name')}", []).append(c.get("version"))
    for name, version in EXPECTED.items():
        if resolved.get(name) != [version]:
            errors.append(f"expected exactly one {name} {version}")
    for name, versions in resolved.items():
        if name.startswith("io.opentelemetry:") and versions != ["1.68.0"]:
            errors.append(f"unaligned OpenTelemetry component {name}")
    for name in EXPECTED:
        group, artifact = name.split(":")
        expected = f"pkg:maven/{group}/{artifact}@{EXPECTED[name]}"
        if sum((row.get("purl") or "").split("?")[0] == expected for row in review.get("libraries", [])) != 1:
            errors.append(f"missing or duplicate packaged {name}")
    if review.get("unmappedPackagedLibraries") or review.get("scanMatchedPackagedLibraries") != review.get("packageLibraryCount"):
        errors.append("incomplete packaged scan coverage")
    if review.get("bomComponentCount") != len(components) or not components:
        errors.append("inconsistent BOM component count")
    scanned = set()
    ids = set()
    for result in scan.get("results", []):
        for row in result.get("packages", []):
            p = row.get("package", {})
            if p.get("ecosystem") == "Maven":
                scanned.add((p.get("name"), p.get("version")))
            for v in row.get("vulnerabilities", []):
                ids.add(v.get("id"))
                ids.update(v.get("aliases", []))
    required = {(f"{c.get('group')}:{c.get('name')}", c.get("version")) for c in components}
    if not required.issubset(scanned):
        errors.append("incomplete full BOM scan coverage")
    if meta.get("status") != "NO_FINDINGS_REVIEW_REQUIRED" or meta.get("advisoryCount") != 0 or ids:
        errors.append("scan unavailable or unresolved advisories")
    if BASELINE & ids:
        errors.append("A16 baseline advisories remain")
    if review.get("decision") != "NO-GO":
        errors.append("unexpected release decision")
    return errors
def digest(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()
def main(target):
    paths = {k: target / v for k, v in {"review": "staging-release-review.json", "meta": "staging-osv-meta.json",
        "bom": "bom.json", "scan": "staging-osv.json", "license": "staging-license-evidence.json"}.items()}
    data = {k: json.loads(p.read_text(encoding="utf-8")) for k, p in paths.items()}
    review, meta, bom, scan = (data[k] for k in ("review", "meta", "bom", "scan"))
    errors = evaluate(review, meta, bom, scan)
    if meta.get("reportSha256") != digest(paths["scan"]) or meta.get("bomSha256") != digest(paths["bom"]):
        errors.append("scan evidence digest mismatch")
    if review.get("artifactSha256") != digest(target / "file-storage-runtime-0.0.1-SNAPSHOT.jar"):
        errors.append("runtime digest mismatch")
    if review.get("licenseEvidenceSha256") != digest(paths["license"]) or data["license"].get("noticeArchiveSha256") != digest(target / "staging-license-notices.zip"):
        errors.append("license evidence digest mismatch")
    if errors:
        raise SystemExit("A17 security regression gate: " + "; ".join(errors))
    print("A17 security regression gate: all seven baseline advisories absent, zero reported findings, full BOM/package coverage, pinned artifacts verified; release NO-GO")
class GateTests(unittest.TestCase):
    def fixture(self):
        components = [{"group": k.split(":")[0], "name": k.split(":")[1], "version": v} for k,v in EXPECTED.items()]
        libraries = [{"purl": f"pkg:maven/{k.replace(':','/')}@{v}?type=jar"} for k,v in EXPECTED.items()]
        review = {"libraries": libraries, "unmappedPackagedLibraries": [], "scanMatchedPackagedLibraries": 3,
                  "packageLibraryCount": 3, "bomComponentCount": 3, "decision": "NO-GO"}
        meta = {"status": "NO_FINDINGS_REVIEW_REQUIRED", "advisoryCount": 0}
        scan = {"results":[{"packages":[{"package":{"ecosystem":"Maven","name":k,"version":v}} for k,v in EXPECTED.items()]}]}
        return review, meta, {"components": components}, scan
    def test_clean(self):
        self.assertEqual([], evaluate(*self.fixture()))
    def test_reject_unavailable(self):
        r,m,b,s = self.fixture(); m["status"] = "UNAVAILABLE"
        self.assertTrue(evaluate(r,m,b,s))
    def test_reject_regression_and_partial_scan(self):
        r,m,b,s = self.fixture(); b["components"][0]["version"] = "1.79"; s["results"][0]["packages"].pop()
        self.assertTrue(evaluate(r,m,b,s))
    def test_reject_new_or_baseline_advisory(self):
        for advisory in ("GHSA-qp49-qgx5-5m26", "GHSA-new-fixture"):
            r,m,b,s = self.fixture(); s["results"][0]["packages"][0]["vulnerabilities"] = [{"id": advisory}]
            self.assertTrue(evaluate(r,m,b,s))
    def test_reject_wrong_release_decision(self):
        r,m,b,s = self.fixture(); r["decision"] = "GO"
        self.assertTrue(evaluate(r,m,b,s))
if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        unittest.main(argv=[sys.argv[0]])
    else:
        main(pathlib.Path(sys.argv[1]))
