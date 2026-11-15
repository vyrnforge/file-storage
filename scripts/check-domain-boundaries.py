#!/usr/bin/env python3
"""FS-110A dependency policy: domain + SPI + application + security, no runtime framework/provider SDK."""
from pathlib import Path
import xml.etree.ElementTree as ET
import sys
root = Path(__file__).resolve().parents[1] / "backend"
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
allowed = {
    "file-storage-domain": {("io.github.vyrnforge", "platform-kernel", "compile"), ("org.junit.jupiter", "junit-jupiter", "test")},
    "file-storage-application": {("io.vyrnforge.filestorage", "file-storage-domain", "compile"), ("io.github.vyrnforge", "platform-kernel", "compile"), ("org.junit.jupiter", "junit-jupiter", "test")},
    "file-storage-security": {("io.vyrnforge.filestorage", "file-storage-domain", "compile"), ("io.vyrnforge.filestorage", "file-storage-application", "compile"), ("io.github.vyrnforge", "platform-kernel", "compile"), ("org.junit.jupiter", "junit-jupiter", "test")},
    "file-storage-provider-spi": {("io.vyrnforge.filestorage", "file-storage-domain", "compile"), ("org.junit.jupiter", "junit-jupiter", "test")},
}
for module, expected in allowed.items():
    pom = ET.parse(root / module / "pom.xml")
    actual = {(d.findtext("m:groupId", namespaces=ns), d.findtext("m:artifactId", namespaces=ns), d.findtext("m:scope", default="compile", namespaces=ns)) for d in pom.findall("m:dependencies/m:dependency", ns)}
    if actual != expected:
        sys.exit(f"Unexpected dependency boundary in {module}: {actual}")
    for path in (root / module / "src/main/java").rglob("*.java"):
        for line in path.read_text().splitlines():
            if line.strip().startswith("import "):
                prefixes = ("java.", "io.vyrnforge.platform.kernel.", "io.vyrnforge.filestorage.domain.")
                if module == "file-storage-security":
                    prefixes += ("io.vyrnforge.filestorage.application.",)
                if not any(line.strip().startswith("import " + prefix) for prefix in prefixes):
                    sys.exit(f"Unexpected import in {path}: {line}")
print("FS-110A domain, provider SPI, application and security dependency boundaries OK")
