#!/usr/bin/env python3
"""FS-120B2B dependency policy: domain + SPI + application + security + persistence, SDK confined to reviewed provider module."""
from pathlib import Path
import xml.etree.ElementTree as ET
import sys
root = Path(__file__).resolve().parents[1] / "backend"
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
allowed = {
    "file-storage-runtime": {("org.testcontainers", "postgresql", "test"), ("io.vyrnforge.filestorage", "file-storage-persistence", "compile"), ("org.springframework.boot", "spring-boot-starter-webmvc", "compile"), ("org.junit.jupiter", "junit-jupiter", "test")},
    "file-storage-domain": {("io.github.vyrnforge", "platform-kernel", "compile"), ("org.junit.jupiter", "junit-jupiter", "test")},
    "file-storage-application": {("io.vyrnforge.filestorage", "file-storage-domain", "compile"), ("io.github.vyrnforge", "platform-kernel", "compile"), ("org.junit.jupiter", "junit-jupiter", "test")},
    "file-storage-security": {("com.nimbusds", "nimbus-jose-jwt", "compile"), ("io.vyrnforge.filestorage", "file-storage-domain", "compile"), ("io.vyrnforge.filestorage", "file-storage-application", "compile"), ("io.github.vyrnforge", "platform-kernel", "compile"), ("org.junit.jupiter", "junit-jupiter", "test")},
    "file-storage-persistence": {("io.vyrnforge.filestorage", "file-storage-security", "compile"), ("io.github.vyrnforge", "platform-kernel-jdbc", "compile"), ("io.vyrnforge.filestorage", "file-storage-domain", "compile"), ("io.vyrnforge.filestorage", "file-storage-application", "compile"), ("org.flywaydb", "flyway-core", "compile"), ("org.flywaydb", "flyway-database-postgresql", "runtime"), ("org.postgresql", "postgresql", "runtime"), ("org.testcontainers", "postgresql", "test"), ("org.junit.jupiter", "junit-jupiter", "test")},
    "file-storage-provider-oss": {("io.vyrnforge.filestorage", "file-storage-provider-spi", "compile"), ("io.vyrnforge.filestorage", "file-storage-provider-spi", "test"), ("com.aliyun.oss", "aliyun-sdk-oss", "compile"), ("org.junit.jupiter", "junit-jupiter", "test")},
    "file-storage-provider-s3": {("io.vyrnforge.filestorage", "file-storage-provider-spi", "compile"), ("io.vyrnforge.filestorage", "file-storage-provider-spi", "test"), ("software.amazon.awssdk", "s3", "compile"), ("software.amazon.awssdk", "apache-client", "compile"), ("org.testcontainers", "testcontainers", "test"), ("org.junit.jupiter", "junit-jupiter", "test")},
    "file-storage-provider-spi": {("io.vyrnforge.filestorage", "file-storage-domain", "compile"), ("org.junit.jupiter", "junit-jupiter", "test")},
}
reactor = ET.parse(root / "pom.xml")
modules = {node.text for node in reactor.findall("m:modules/m:module", ns)}
if modules != set(allowed):
    sys.exit(f"Unexpected Maven reactor modules: {modules}")
for module, expected in allowed.items():
    pom = ET.parse(root / module / "pom.xml")
    actual = {(d.findtext("m:groupId", namespaces=ns), d.findtext("m:artifactId", namespaces=ns), d.findtext("m:scope", default="compile", namespaces=ns)) for d in pom.findall("m:dependencies/m:dependency", ns)}
    if actual != expected:
        sys.exit(f"Unexpected dependency boundary in {module}: {actual}")
    for path in (root / module / "src/main/java").rglob("*.java"):
        for line in path.read_text().splitlines():
            if line.strip().startswith("import "):
                prefixes = ("java.", "io.vyrnforge.platform.kernel.", "io.vyrnforge.filestorage.domain.")
                if module == "file-storage-provider-oss":
                    prefixes += ("io.vyrnforge.filestorage.spi.", "com.aliyun.oss.")
                if module == "file-storage-provider-s3":
                    prefixes += ("io.vyrnforge.filestorage.spi.", "software.amazon.awssdk.")
                if module == "file-storage-security":
                    prefixes += ("io.vyrnforge.filestorage.application.", "io.vyrnforge.filestorage.security.", "com.nimbusds.")
                if module == "file-storage-persistence":
                    prefixes += ("javax.sql.", "org.flywaydb.", "io.vyrnforge.filestorage.application.", "io.vyrnforge.filestorage.security.")
                if module == "file-storage-runtime":
                    prefixes += ("javax.sql.", "jakarta.servlet.", "org.springframework.", "tools.jackson.core.", "tools.jackson.databind.", "io.vyrnforge.filestorage.runtime.", "io.vyrnforge.filestorage.application.", "io.vyrnforge.filestorage.security.", "io.vyrnforge.filestorage.persistence.")
                if not any(line.strip().startswith("import " + prefix) for prefix in prefixes):
                    sys.exit(f"Unexpected import in {path}: {line}")
print("FS-300A domain, provider SPI, S3/OSS adapters, application, security, persistence and runtime dependency boundaries OK")
