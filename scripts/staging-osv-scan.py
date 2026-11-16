#!/usr/bin/env python3
"""Candidate-only OSV evidence: public database download; scan runs without network.
Never send the private SBOM to an external API. Findings/scan failure do not authorize release.
"""
import datetime
import hashlib
import json
import os
import pathlib
import subprocess
import sys
import tempfile

target = pathlib.Path(sys.argv[1]).resolve()
bom = target / "bom.json"
report = target / "staging-osv.json"
meta = target / "staging-osv-meta.json"
image = "ghcr.io/google/osv-scanner@sha256:a70ffffc2bfd33482953e5d6b64c6fcdb30239717a07b0f0d3ab2859ce3e4cde"
database_url = "https://osv-vulnerabilities.storage.googleapis.com/Maven/all.zip"

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def write(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")

evidence = {"format": "file-storage-osv-evidence-v1", "scannerImage": image, "databaseUrl": database_url,
            "bomSha256": digest(bom), "status": "UNAVAILABLE", "scanNetwork": "none",
            "reviewDecision": "NO-GO"}
phase = "download public Maven database"
try:
    with tempfile.TemporaryDirectory(prefix="fs-osv-") as root:
        db = pathlib.Path(root) / "osv-scalibr" / "Maven" / "all.zip"
        db.parent.mkdir(parents=True)
        subprocess.run(["curl", "--fail", "--silent", "--show-error", "--location", "--proto", "=https",
                        "--proto-redir", "=https", "--max-time", "90", "--max-filesize", "536870912",
                        "--output", str(db), database_url], check=True, timeout=100)
        evidence["databaseSha256"] = digest(db)
        evidence["databaseFetchedAt"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        phase = "pull pinned scanner image"
        subprocess.run(["docker", "pull", "--platform", "linux/amd64", image], check=True, timeout=120)
        phase = "offline SBOM scan"
        with report.open("wb") as output:
            result = subprocess.run(["docker", "run", "--rm", "--platform", "linux/amd64",
                "--network", "none", "--read-only", "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges", "--tmpfs", "/tmp:rw,noexec,nosuid,size=128m",
                "--user", f"{os.getuid()}:{os.getgid()}",
                "-e", "HOME=/tmp", "-e", "OSV_SCANNER_LOCAL_DB_CACHE_DIRECTORY=/db",
                "-v", f"{bom}:/input/bom.json:ro", "-v", f"{root}:/db:ro",
                image, "scan", "source", "--offline", "--format=json", "--all-packages", "-L", "/input/bom.json"],
                stdout=output, timeout=120)
        evidence["scannerExitCode"] = result.returncode
        if result.returncode not in (0, 1):
            raise RuntimeError("scanner did not complete")
        phase = "validate scanner report"
        parsed = json.loads(report.read_text(encoding="utf-8"))
        packages = [p for result in parsed.get("results", []) for p in result.get("packages", [])]
        if not packages or not all(isinstance(p.get("package"), dict) for p in packages):
            raise RuntimeError("empty or invalid package report")
        ids = sorted({v["id"] for p in packages for v in p.get("vulnerabilities", []) if v.get("id")})
        if result.returncode == 1 and not ids:
            raise RuntimeError("findings exit without findings")
        evidence.update(status="FINDINGS_REVIEW_REQUIRED" if ids else "NO_FINDINGS_REVIEW_REQUIRED",
                        packageCount=len(packages), advisoryCount=len(ids), advisoryIds=ids)
except (OSError, ValueError, RuntimeError, subprocess.SubprocessError):
    evidence["unavailablePhase"] = phase
    write(report, {"results": [], "scanStatus": "UNAVAILABLE"})
evidence["reportSha256"] = digest(report)
write(meta, evidence)
print(f"Offline OSV evidence: {evidence['status']}; packages={evidence.get('packageCount', 0)}; advisories={evidence.get('advisoryCount', 0)}; release NO-GO")
