#!/usr/bin/env python3
"""Artifact-bound license/notice inventory, not a legal clearance or release."""
import hashlib
import io
import json
import pathlib
import re
import sys
import urllib.request
import zipfile

target = pathlib.Path(sys.argv[1]).resolve()
jar = target / "file-storage-runtime-0.0.1-SNAPSHOT.jar"
bom = json.loads((target / "bom.json").read_text(encoding="utf-8"))
repo = pathlib.Path(__file__).resolve().parents[1]
def sha(data):
    return hashlib.sha256(data).hexdigest()
def notice_name(name):
    return bool(re.match(r"(?i)^(license|licence|notice|copying|copyright|third[-_ ]party|dependencies)(?:[._ -].*)?$",
                         pathlib.PurePosixPath(name).name))
def fetch(url, limit):
    with urllib.request.urlopen(url, timeout=25) as response:
        if response.geturl() != url:
            raise ValueError("unexpected source archive redirect")
        data = response.read(limit + 1)
    if len(data) > limit:
        raise ValueError("source archive exceeds limit")
    return data
by_file = {}
for c in bom["components"]:
    by_file.setdefault(f"{c.get('name')}-{c.get('version')}.jar", []).append(c)
# Source-only evidence for the two historical Maven artifacts whose POM lacks a license.
# Never send SBOM, dependency inventory, credentials or repository content to a service.
source_coords = {("com.aliyun", "aliyun-java-sdk-kms", "2.11.0"),
                 ("com.aliyun", "aliyun-java-sdk-ram", "3.1.0")}
archive = target / "staging-license-notices.zip"
manifest = {"format": "file-storage-license-evidence-v1", "artifactSha256": sha(jar.read_bytes()),
            "bomSha256": sha((target / "bom.json").read_bytes()), "decision": "REVIEW_REQUIRED", "libraries": []}
total = 0
with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as out:
    def collect(z, prefix):
        global total
        records = []
        for i, entry in enumerate(z.infolist()):
            if entry.is_dir() or not notice_name(entry.filename):
                continue
            if entry.file_size > 2_000_000 or total + entry.file_size > 50_000_000:
                raise SystemExit("license/notice evidence exceeds size budget")
            data = z.read(entry)
            total += len(data)
            dest = f"{prefix}/{i:04d}-{pathlib.PurePosixPath(entry.filename).name}"
            out.writestr(dest, data)
            records.append({"entry": entry.filename, "archiveEntry": dest, "sha256": sha(data), "bytes": len(data)})
        return records
    license_bytes = (repo / "LICENSE").read_bytes()
    out.writestr("first-party/LICENSE", license_bytes)
    manifest["repositoryLicenseSha256"] = sha(license_bytes)
    with zipfile.ZipFile(jar) as outer:
        manifest["runtimeNotices"] = collect(outer, "runtime")
        for index, name in enumerate(sorted(n for n in outer.namelist() if n.startswith("BOOT-INF/lib/") and n.endswith(".jar"))):
            payload = outer.read(name)
            matches = by_file.get(pathlib.PurePosixPath(name).name, [])
            if len(matches) != 1:
                raise SystemExit(f"unmapped license component: {name}")
            c = matches[0]
            coord = (c.get("group"), c.get("name"), c.get("version"))
            licenses = c.get("licenses", [])
            row = {"path": name, "sha256": sha(payload), "bomRef": c["bom-ref"], "licenses": licenses,
                   "disposition": "DECLARED_REVIEW_REQUIRED" if licenses else "MISSING_METADATA"}
            with zipfile.ZipFile(io.BytesIO(payload)) as nested:
                row["embeddedNotices"] = collect(nested, f"libraries/{index:04d}")
            if c.get("group") == "io.vyrnforge.filestorage":
                row["disposition"] = "FIRST_PARTY_APACHE_2_0"
                row["repositoryLicenseSha256"] = sha(license_bytes)
            elif coord in source_coords:
                g, a, v = coord
                url = f"https://repo.maven.apache.org/maven2/{g.replace('.', '/')}/{a}/{v}/{a}-{v}-sources.jar"
                source = {"url": url, "status": "UNAVAILABLE", "upstreamProject": "https://github.com/aliyun/aliyun-openapi-java-sdk"}
                try:
                    data = fetch(url, 25_000_000)
                    expected = fetch(url + ".sha1", 1024).decode("ascii").strip().split()[0]
                    if not re.fullmatch(r"[0-9a-fA-F]{40}", expected) or hashlib.sha1(data).hexdigest() != expected.lower():
                        raise ValueError("source archive checksum mismatch")
                    source["sha256"] = sha(data)
                    source["mavenCentralSha1"] = expected.lower()
                    with zipfile.ZipFile(io.BytesIO(data)) as sources:
                        java = [e for e in sources.infolist() if e.filename.endswith(".java") and not e.is_dir()]
                        if len(java) > 20000:
                            raise ValueError("source archive exceeds entry budget")
                        headers = []
                        for e in java:
                            with sources.open(e) as f:
                                head = f.read(4096)
                            if b"Apache License, Version 2.0" in head or b"Licensed under the Apache License, Version 2.0" in head:
                                headers.append(e.filename)
                        source["javaFiles"] = len(java)
                        source["apache2HeaderFiles"] = len(headers)
                        source["exampleHeaderEntries"] = headers[:5]
                        source["notices"] = collect(sources, f"sources/{index:04d}")
                        source["status"] = "CORROBORATED_APACHE_2_0" if java and len(headers) == len(java) else "REVIEW_REQUIRED"
                    if source["status"] == "CORROBORATED_APACHE_2_0":
                        row["disposition"] = "UPSTREAM_APACHE_2_0_CORROBORATED"
                        row["licenseEvidence"] = "Exact Maven sources carry Apache-2.0 headers; upstream project declares Apache-2.0."
                except (OSError, ValueError, IndexError, zipfile.BadZipFile) as e:
                    source["failureType"] = type(e).__name__
                row["sourceEvidence"] = source
            if len(licenses) > 1 or any(" OR " in x.get("expression", "") or " AND " in x.get("expression", "") for x in licenses):
                row["disposition"] = "MULTI_LICENSE_REVIEW_REQUIRED"
            manifest["libraries"].append(row)
    out.writestr("license-manifest.json", json.dumps(manifest, indent=2, sort_keys=True) + "\n")
manifest["noticeArchiveSha256"] = sha(archive.read_bytes())
(target / "staging-license-evidence.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
counts = {}
for row in manifest["libraries"]:
    counts[row["disposition"]] = counts.get(row["disposition"], 0) + 1
print("License evidence:", json.dumps(counts, sort_keys=True))
print(f"Notice archive SHA256 {manifest['noticeArchiveSha256']}; distribution approval remains outstanding")
