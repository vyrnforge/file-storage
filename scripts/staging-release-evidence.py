#!/usr/bin/env python3
"""Bind the real packaged JAR inventory and CycloneDX BOM to CI source evidence.
A candidate review manifest, NOT a vulnerability scan, signature or deployment approval.
"""
import hashlib
import json
import os
import pathlib
import subprocess
import sys
import zipfile

target = pathlib.Path(sys.argv[1])
jar = target / "file-storage-runtime-0.0.1-SNAPSHOT.jar"
inventory_path = target / "staging-inventory.json"
bom_path = target / "bom.json"
def sha(data):
    return hashlib.sha256(data).hexdigest()
def digest(path):
    return sha(path.read_bytes())
def write(name, content):
    (target / name).write_text(json.dumps(content, sort_keys=True, indent=2) + "\n", encoding="utf-8")
def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()

inventory = json.loads(inventory_path.read_text(encoding="utf-8"))
bom = json.loads(bom_path.read_text(encoding="utf-8"))
if inventory["sha256"] != digest(jar) or bom.get("bomFormat") != "CycloneDX" or bom.get("specVersion") != "1.6":
    raise SystemExit("release evidence mismatch")
components = bom.get("components", [])
if not components or not bom.get("dependencies"):
    raise SystemExit("empty CycloneDX dependency graph")
by_file = {}
for c in components:
    name, version = c.get("name"), c.get("version")
    if name and version:
        by_file.setdefault(f"{name}-{version}.jar", []).append(c)
entries = []
with zipfile.ZipFile(jar) as z:
    actual = sorted(n for n in z.namelist() if n.startswith("BOOT-INF/lib/") and n.endswith(".jar"))
    if actual != sorted(x["path"] for x in inventory["libraries"]):
        raise SystemExit("packaged library inventory mismatch")
    for lib in inventory["libraries"]:
        name = pathlib.PurePosixPath(lib["path"]).name
        if sha(z.read(lib["path"])) != lib["sha256"]:
            raise SystemExit("packaged library checksum mismatch")
        matches = by_file.get(name, [])
        component = matches[0] if len(matches) == 1 else {}
        entries.append({**lib, "bomRef": component.get("bom-ref"), "purl": component.get("purl"),
                        "licenseMetadata": component.get("licenses", []),
                        "vulnerabilityDisposition": "UNREVIEWED", "licenseDisposition": "UNREVIEWED"})
unmapped = [e["path"] for e in entries if not e["bomRef"]]
used = {e["bomRef"] for e in entries if e["bomRef"]}
extra = sorted(c.get("bom-ref", c.get("name", "unknown")) for c in components if c.get("bom-ref") not in used)
event = {}
if os.environ.get("GITHUB_EVENT_PATH"):
    event = json.loads(pathlib.Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
pr = event.get("pull_request", {})
provenance = {"format": "file-storage-build-evidence-v1", "attestation": "UNSIGNED",
              "checkoutCommit": git("rev-parse", "HEAD"), "checkoutTree": git("rev-parse", "HEAD^{tree}"),
              "pullRequestHead": pr.get("head", {}).get("sha"), "pullRequestBase": pr.get("base", {}).get("sha"),
              "workflowRun": os.environ.get("GITHUB_RUN_ID"), "workflowAttempt": os.environ.get("GITHUB_RUN_ATTEMPT"),
              "artifact": jar.name, "artifactSha256": digest(jar), "inventorySha256": digest(inventory_path),
              "cycloneDxSha256": digest(bom_path), "buildCommand": "make ci"}
write("staging-provenance.json", provenance)
write("staging-release-review.json", {"format": "file-storage-release-review-v1",
    "decision": "NO-GO", "artifactSha256": digest(jar), "cycloneDxSha256": digest(bom_path),
    "packageLibraryCount": len(entries), "bomComponentCount": len(components),
    "unmappedPackagedLibraries": unmapped, "unmappedBomComponents": extra,
    "vulnerabilityScan": "NOT_RUN", "licenseReview": "UNREVIEWED", "signature": "NOT_SIGNED",
    "libraries": entries})
print(f"Release evidence: {len(entries)} packaged libraries, {len(components)} BOM components; {len(unmapped)} unmapped libraries, {len(extra)} unmatched BOM components; NO-GO pending review")
print(f"CycloneDX SHA256 {digest(bom_path)}; checkout {provenance['checkoutCommit']}; provenance unsigned")
