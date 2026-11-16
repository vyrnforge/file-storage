#!/usr/bin/env python3
"""Exact-component engineering distribution review. Never grants legal/release approval."""
import argparse
import collections
import concurrent.futures
import hashlib
import io
import json
import pathlib
import re
import urllib.request
import zipfile

def sha(data):
    return hashlib.sha256(data).hexdigest()

def require(condition, message):
    if not condition:
        raise ValueError(message)

def read_json(data):
    def unique(pairs):
        result = {}
        for k, v in pairs:
            require(k not in result, "duplicate JSON key")
            result[k] = v
        return result
    return json.loads(data, object_pairs_hook=unique)

def dump(data):
    return (json.dumps(data, sort_keys=True, indent=2) + "\n").encode()

def license_names(row):
    return [x.get("expression") or x.get("license", {}).get("id") or
            x.get("license", {}).get("name") or "UNKNOWN" for x in row["licenses"]]

def obligations(row):
    ref = row["bomRef"]
    names = license_names(row)
    if row["disposition"] == "FIRST_PARTY_APACHE_2_0":
        return "FIRST_PARTY", "Apache-2.0; include root LICENSE, applicable NOTICE and changed-file notices if source is distributed."
    if "aliyun-java-sdk-kms@2.11.0?" in ref or "aliyun-java-sdk-ram@3.1.0?" in ref:
        return "SOURCE_CORROBORATION", "Apache-2.0 source headers corroborate; retain copyright, full license and notices; review non-Java files and exact source terms."
    if "/FastInfoset@1.2.16?" in ref:
        return "TERMS_CONFLICT", "BOM Apache-2.0 + BSD-3-Clause; embedded LICENSE Apache-2.0, NOTICE declares BSD-3-Clause. Retain both texts and resolve component/third-party scope; do not assume OR."
    if "javax.activation/javax.activation-api@1.2.0?" in ref:
        return "TERMS_CONFLICT", "BOM CDDL-1.0 OR GPL with classpath exception; embedded license begins CDDL 1.1. Resolve version and actual election; covered source/offer and exception obligations require review."
    if "javax.xml.bind/jaxb-api@2.3.1?" in ref:
        return "SOURCE_OBLIGATIONS", "CDDL 1.1 or GPL with classpath exception as applicable; confirm election, covered source, notices and source availability for redistribution."
    if "jakarta.annotation/jakarta.annotation-api@3.0.0?" in ref:
        return "SOURCE_OBLIGATIONS", "EPL-2.0; secondary GPL with classpath exception conditional per NOTICE. Prefer EPL only after review; make covered source available and preserve notices on redistribution."
    if ref.split("?")[0] in ("pkg:maven/ch.qos.logback/logback-core@1.5.38", "pkg:maven/ch.qos.logback/logback-classic@1.5.38"):
        return "SOURCE_OBLIGATIONS", "Declared EPL-2.0/LGPL-2.1; verify exact upstream license/election. Prefer EPL if allowed; satisfy covered source and notices, or LGPL relinking/source requirements if elected."
    if "org.dom4j/dom4j@2.1.4?" in ref:
        return "CUSTOM_TERMS", "Plexus metadata label is not exact DOM4J license evidence; obtain version-specific copyright/terms and confirm redistribution conditions."
    if "org.bouncycastle/bcprov-jdk18on@1.87?" in ref:
        return "CUSTOM_TERMS", "Embedded LICENSE.md is MIT-form; preserve exact copyright/permission text, review crypto/export and retain binary license-class evidence separately."
    if "org.jdom/jdom2@2.0.6.1?" in ref:
        return "CUSTOM_TERMS", "Embedded JDOM custom terms: reproduce copyright, conditions/disclaimer in binary materials; no endorsement or JDOM naming without permission; acknowledgment requested."
    if len(names) != 1 or " OR " in names[0] or " AND " in names[0]:
        return "TERMS_UNRESOLVED", "Resolve exact terms/election; retain all notices; determine source and redistribution requirements."
    name = names[0]
    if name == "Apache-2.0":
        return "PERMISSIVE", "Apache-2.0: include license, preserve applicable NOTICE and attribution; mark modifications and retain source notices when applicable."
    if name in ("BSD-2-Clause", "BSD-3-Clause", "MIT", "MIT-0"):
        return "PERMISSIVE", name + ": retain applicable copyright, permission/conditions and disclaimer; verify exact text and non-endorsement if applicable."
    return "TERMS_UNRESOLVED", "Review exact terms, copyright, attribution and redistribution/source conditions."

def is_text(entry, data):
    if pathlib.PurePosixPath(entry).suffix.lower() in (".jar", ".class", ".zip", ".so", ".dll"):
        return False
    try:
        data.decode("utf-8")
        return b"\x00" not in data
    except UnicodeDecodeError:
        return False

def fetch(url, limit):
    with urllib.request.urlopen(url, timeout=12) as response:
        require(response.geturl() == url, "unexpected redirect")
        data = response.read(limit + 1)
    require(len(data) <= limit, "source budget exceeded")
    return data

def source_terms(component):
    g, a, v = (component.get(k, "") for k in ("group", "name", "version"))
    require(re.fullmatch(r"[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*", g) and
            all(re.fullmatch(r"[A-Za-z0-9_.+-]+", s) and s not in (".", "..") for s in (a, v)), "invalid Maven coordinates")
    url = "https://repo.maven.apache.org/maven2/" + g.replace(".", "/") + f"/{a}/{v}/{a}-{v}-sources.jar"
    evidence = {"url": url, "status": "UNAVAILABLE", "documents": []}
    payloads = []
    try:
        data = fetch(url, 25_000_000)
        expected = fetch(url + ".sha1", 1024).decode("ascii").strip().split()[0].lower()
        require(re.fullmatch(r"[0-9a-f]{40}", expected) and hashlib.sha1(data).hexdigest() == expected, "source checksum mismatch")
        evidence.update(sha256=sha(data), mavenCentralSha1=expected)
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            require(len(archive.infolist()) <= 20000, "source entry budget exceeded")
            total = 0
            for entry in archive.infolist():
                if entry.is_dir() or not re.match(r"(?i)^(license|licence|notice|copying|copyright|third[-_ ]party)(?:[._ -].*)?$", pathlib.PurePosixPath(entry.filename).name):
                    continue
                require(entry.file_size <= 2_000_000 and total + entry.file_size <= 5_000_000, "source notice budget exceeded")
                raw = archive.read(entry)
                total += len(raw)
                if is_text(entry.filename, raw):
                    payloads.append((entry.filename, raw))
        evidence["status"] = "TEXT_COLLECTED_REVIEW_REQUIRED" if payloads else "NO_TEXT_IN_SOURCE_ARCHIVE"
    except (OSError, ValueError, IndexError, zipfile.BadZipFile, RuntimeError) as e:
        evidence["failureType"] = type(e).__name__
        payloads = []
    return evidence, payloads

def build(target, supplemental=False):
    def data(name):
        return (target / name).read_bytes()
    m = read_json(data("staging-license-evidence.json"))
    inv = read_json(data("staging-inventory.json"))
    bom = read_json(data("bom.json"))
    prov = read_json(data("staging-provenance.json"))
    raw_archive = data("staging-license-notices.zip")
    require(m["artifactSha256"] == prov["artifactSha256"], "runtime binding")
    require(m["bomSha256"] == prov["cycloneDxSha256"] == sha(data("bom.json")), "BOM binding")
    require(prov["inventorySha256"] == sha(data("staging-inventory.json")), "inventory binding")
    require(prov["licenseEvidenceSha256"] == sha(data("staging-license-evidence.json")), "license evidence binding")
    require(m["noticeArchiveSha256"] == prov["licenseNoticeArchiveSha256"] == sha(raw_archive), "notice archive binding")
    actual = {r["path"]: r["sha256"] for r in inv["libraries"]}
    require(len(actual) == len(inv["libraries"]) == len(m["libraries"]), "inventory count")
    require(actual == {r["path"]: r["sha256"] for r in m["libraries"]}, "inventory coverage")
    components = {c["bom-ref"]: c for c in bom["components"]}
    require(len(components) == len(bom["components"]), "duplicate BOM component")
    require(len({r["bomRef"] for r in m["libraries"]}) == len(m["libraries"]), "duplicate packaged reference")
    report = {"format": "file-storage-distribution-review-v1", "decision": "NO-GO",
              "scope": "engineering review of packaged Maven dependencies; no distribution approval",
              "artifactSha256": m["artifactSha256"], "bomSha256": m["bomSha256"],
              "licenseEvidenceSha256": sha(data("staging-license-evidence.json")),
              "originalNoticeArchiveSha256": sha(raw_archive), "libraries": [], "graphOnly": [],
              "excludedBinaryOrNonUtf8Evidence": [], "runtimeNotices": [],
              "limitations": ["No external distribution approval, no complete shaded/native/OS inventory, no source-election approval.",
                              "Source archive checksums corroborate exact public Maven coordinates, not binary-to-source reproducibility.",
                              "Collected source terms must be reviewed for applicability; missing evidence blocks release."]}
    todo = [r for r in m["libraries"] if r["disposition"] != "FIRST_PARTY_APACHE_2_0" and
            (not r["embeddedNotices"] or obligations(r)[0] in ("TERMS_CONFLICT", "SOURCE_OBLIGATIONS", "CUSTOM_TERMS"))]
    sources = {}
    if supplemental:
        with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
            results = pool.map(lambda r: source_terms(components[r["bomRef"]]), todo)
            sources = dict(zip((r["bomRef"] for r in todo), results))
    with zipfile.ZipFile(io.BytesIO(raw_archive)) as archive, zipfile.ZipFile(
            target / "staging-distribution-notices.zip", "w", compression=zipfile.ZIP_DEFLATED) as out:
        require(len(archive.namelist()) == len(set(archive.namelist())), "duplicate notice entries")
        internal = read_json(archive.read("license-manifest.json"))
        require(internal == {k: v for k, v in m.items() if k != "noticeArchiveSha256"}, "embedded notice manifest mismatch")
        consumed = set()
        def retain(doc, destination):
            entry = doc["archiveEntry"]
            require(entry not in consumed, "duplicate document binding")
            consumed.add(entry)
            raw = archive.read(entry)
            require(sha(raw) == doc["sha256"] and len(raw) == doc["bytes"], "notice document digest")
            if is_text(doc["entry"], raw):
                out.writestr(destination, raw)
                return {**doc, "distributionEntry": destination}
            report["excludedBinaryOrNonUtf8Evidence"].append(doc)
            return {**doc, "distributionEntry": None}
        root = archive.read("first-party/LICENSE")
        require(sha(root) == m["repositoryLicenseSha256"], "root license digest")
        out.writestr("first-party/LICENSE", root)
        report["repositoryLicenseSha256"] = sha(root)
        for i, doc in enumerate(m["runtimeNotices"]):
            report["runtimeNotices"].append(retain(doc, f"runtime/{i:04d}"))
        for i, row in enumerate(m["libraries"]):
            c = components[row["bomRef"]]
            require(c.get("licenses", []) == row["licenses"], "license metadata mismatch")
            family, obligation = obligations(row)
            docs = [retain(d, f"libraries/{i:04d}/{j:04d}") for j, d in enumerate(
                row["embeddedNotices"] + row.get("sourceEvidence", {}).get("notices", []))]
            source = None
            if row["bomRef"] in sources:
                source, payloads = sources[row["bomRef"]]
                for j, (name, raw) in enumerate(payloads):
                    dest = f"sources/{i:04d}/{j:04d}"
                    out.writestr(dest, raw)
                    source["documents"].append({"entry": name, "distributionEntry": dest, "sha256": sha(raw), "bytes": len(raw)})
            report["libraries"].append({"bomRef": row["bomRef"], "path": row["path"], "sha256": row["sha256"],
                "declaredLicenses": row["licenses"], "engineeringClassification": family, "obligations": obligation,
                "disposition": "REVIEW_REQUIRED", "documents": docs, "supplementalSource": source,
                "originalSourceEvidence": row.get("sourceEvidence"),
                "evidenceStatus": "FIRST_PARTY_ROOT_LICENSE" if family == "FIRST_PARTY" else
                    "EMBEDDED_TEXT_PRESENT" if any(d["distributionEntry"] for d in docs) else
                    "SOURCE_TEXT_REVIEW_REQUIRED" if source and source["documents"] else "MISSING_EXACT_TEXT"})
        require(set(archive.namelist()) == consumed | {"first-party/LICENSE", "license-manifest.json"}, "unaccounted notice entry")
        packaged = {r["bomRef"] for r in m["libraries"]}
        report["graphOnly"] = [{"bomRef": c["bom-ref"], "licenses": c.get("licenses", []),
                               "disposition": "GRAPH_ONLY_REVIEW_REQUIRED"} for c in bom["components"] if c["bom-ref"] not in packaged]
        report["counts"] = dict(collections.Counter(r["evidenceStatus"] for r in report["libraries"]))
        report["classifications"] = dict(collections.Counter(r["engineeringClassification"] for r in report["libraries"]))
        out.writestr("distribution-review.json", dump(report))
    report["distributionNoticeArchiveSha256"] = sha((target / "staging-distribution-notices.zip").read_bytes())
    (target / "staging-distribution-review.json").write_bytes(dump(report))
    print("A18 distribution review:", len(report["libraries"]), "packaged;", len(report["graphOnly"]), "graph-only;", report["counts"], report["classifications"])
    print("Notice candidate SHA256:", report["distributionNoticeArchiveSha256"], "NO-GO; distribution approval outstanding")

def self_test():
    import tempfile
    # Small artifact-bound fixture: notice bytes and all evidence hashes are real.
    with tempfile.TemporaryDirectory() as tmp:
        p = pathlib.Path(tmp)
        doc = b"Copyright Example; permission granted."
        root = b"Apache-2.0 fixture"
        ref = "pkg:maven/example/demo@1?type=jar"
        lic = [{"license": {"id": "MIT"}}]
        row = {"bomRef": ref, "path": "BOOT-INF/lib/demo-1.jar", "sha256": "a" * 64,
               "licenses": lic, "disposition": "DECLARED_REVIEW_REQUIRED",
               "embeddedNotices": [{"entry": "META-INF/LICENSE", "archiveEntry": "libraries/0000/LICENSE",
                                     "sha256": sha(doc), "bytes": len(doc)}]}
        bom = dump({"components": [{"bom-ref": ref, "licenses": lic}]})
        inv = dump({"libraries": [{"path": row["path"], "sha256": row["sha256"]}]})
        m = {"artifactSha256": "b" * 64, "bomSha256": sha(bom), "libraries": [row],
             "runtimeNotices": [], "repositoryLicenseSha256": sha(root)}
        archive = p / "staging-license-notices.zip"
        with zipfile.ZipFile(archive, "w") as z:
            z.writestr("first-party/LICENSE", root)
            z.writestr("libraries/0000/LICENSE", doc)
            z.writestr("license-manifest.json", dump(m))
        m["noticeArchiveSha256"] = sha(archive.read_bytes())
        prov = {"artifactSha256": m["artifactSha256"], "cycloneDxSha256": sha(bom),
                "inventorySha256": sha(inv), "licenseEvidenceSha256": sha(dump(m)),
                "licenseNoticeArchiveSha256": m["noticeArchiveSha256"]}
        for name, content in (("bom.json", bom), ("staging-inventory.json", inv),
                              ("staging-license-evidence.json", dump(m)), ("staging-provenance.json", dump(prov))):
            (p / name).write_bytes(content)
        build(p)
        review = read_json((p / "staging-distribution-review.json").read_bytes())
        require(review["decision"] == "NO-GO" and review["counts"] == {"EMBEDDED_TEXT_PRESENT": 1}, "valid fixture failed")
        require(not is_text("third-party.jar", b"PK"), "binary archive accepted")
        require(not is_text("LICENSE.class", b"\xca\xfe\xba\xbe"), "class accepted")
        require(is_text("LICENSE.txt", doc), "text rejected")
        for name in ("staging-license-notices.zip", "bom.json", "staging-inventory.json", "staging-license-evidence.json"):
            path = p / name
            saved = path.read_bytes()
            path.write_bytes(saved + b" ")
            try:
                build(p)
            except ValueError:
                pass
            else:
                raise AssertionError("tampered evidence accepted: " + name)
            finally:
                path.write_bytes(saved)
    print("A18 distribution review self-tests: 8 passed")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("target", type=pathlib.Path, nargs="?")
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--supplemental-sources", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
    elif args.target:
        build(args.target, args.supplemental_sources)
    else:
        parser.error("target or --self-test required")
