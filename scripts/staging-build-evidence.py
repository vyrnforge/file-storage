#!/usr/bin/env python3
"""Inventory exactly the packaged runtime and nested libraries. No network or publishing."""
import hashlib
import json
import pathlib
import sys
import zipfile

jar = pathlib.Path(sys.argv[1])
output = pathlib.Path(sys.argv[2])
def digest(data):
    return hashlib.sha256(data).hexdigest()

with zipfile.ZipFile(jar) as archive:
    libs = sorted(n for n in archive.namelist() if n.startswith("BOOT-INF/lib/") and n.endswith(".jar"))
    if not libs or not any("file-storage-provider-s3-" in n for n in libs) or not any("file-storage-provider-oss-" in n for n in libs):
        raise SystemExit("staging runtime missing packaged provider libraries")
    report = {"format": "file-storage-runtime-inventory-v1", "artifact": jar.name,
              "sha256": digest(jar.read_bytes()),
              "libraries": [{"path": n, "sha256": digest(archive.read(n))} for n in libs]}
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(f"Packaged runtime inventory: {len(libs)} libraries; artifact SHA256 {report['sha256']}")
