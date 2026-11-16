#!/usr/bin/env python3
"""A19: evidence-only source corroboration and scope-specific proposed dispositions."""
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

def require(ok, message):
    if not ok:
        raise ValueError(message)

def sha(data):
    return hashlib.sha256(data).hexdigest()

def dump(data):
    return (json.dumps(data, indent=2, sort_keys=True) + "\n").encode()

def read(data):
    def unique(pairs):
        out = {}
        for k, v in pairs:
            require(k not in out, "duplicate JSON key")
            out[k] = v
        return out
    return json.loads(data, object_pairs_hook=unique)

def fetch(url, limit):
    with urllib.request.urlopen(url, timeout=15) as response:
        require(response.geturl() == url, "unexpected redirect")
        data = response.read(limit + 1)
    require(len(data) <= limit, "download budget exceeded")
    return data

def java_header(data):
    # Inspect only the leading comment block(s), never search executable code.
    text = data[:16384].decode("utf-8-sig", errors="replace")
    match = re.match(r"\s*(?:(?:/\*[\s\S]*?\*/\s*)|(?://[^\n]*\n\s*))+",
                     text)
    if not match:
        return "NO_LEADING_COMMENT", None
    header = match.group(0).strip()
    lower = header.lower()
    apache = ("apache license" in lower and ("version 2.0" in lower or "apache.org/licenses/license-2.0" in lower)) or bool(re.search(r"spdx-license-identifier:\s*apache-2\.0\b", lower))
    epl = "eclipse public license" in lower or "epl-2.0" in lower
    lgpl = "lesser general public license" in lower or "lgpl-2.1" in lower
    if apache and (epl or lgpl):
        category = "MULTIPLE_TERMS"
    elif apache:
        category = "APACHE_2_0_INDICATOR"
    elif epl or lgpl:
        category = "EPL_LGPL_INDICATOR"
    else:
        category = "OTHER_OR_NO_LICENSE_INDICATOR"
    # Only the license-bearing leading comment is retained as corroboration.
    return category, header if category != "OTHER_OR_NO_LICENSE_INDICATOR" else None

def inspect_source(pin, raw):
    require(sha(raw) == pin["sourceSha256"], "source archive SHA-256 mismatch: " + pin["bomRef"])
    counts = collections.Counter()
    headers = {}
    files = []
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        entries = archive.infolist()
        require(len(entries) <= 25000, "source entry budget")
        require(len({e.filename for e in entries}) == len(entries), "duplicate source entry")
        require(sum(e.file_size for e in entries) <= 150_000_000, "source expanded budget")
        for entry in entries:
            path = pathlib.PurePosixPath(entry.filename)
            require(not path.is_absolute() and ".." not in path.parts, "unsafe source entry")
            if entry.is_dir():
                continue
            if not entry.filename.endswith(".java"):
                files.append(entry.filename)
                continue
            with archive.open(entry) as handle:
                category, header = java_header(handle.read(16384))
            counts[category] += 1
            if header is not None:
                content = header.encode("utf-8")
                digest = sha(content)
                if digest not in headers:
                    headers[digest] = {"text": header, "sampleEntry": entry.filename,
                                       "sha256": digest, "occurrences": 0}
                headers[digest]["occurrences"] += 1
    require(sum(counts.values()) > 0, "no Java source in pinned archive")
    return {"sourceSha256": sha(raw), "javaFiles": sum(counts.values()),
            "headerCounts": dict(counts), "distinctLicenseHeaders": list(headers.values()),
            "nonJavaEntries": files, "status": "CORROBORATION_ONLY",
            "limitations": "Leading comment indicators are not full license review; non-Java entries and source-to-binary correspondence remain unverified. Header excerpts are decoded UTF-8, not original source bytes."}

def source(pin):
    g, a, v = (pin[k] for k in ("group", "artifact", "version"))
    require(re.fullmatch(r"[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*", g)
            and all(re.fullmatch(r"[A-Za-z0-9_.+-]+", s) and s not in (".", "..") for s in (a, v)), "unsafe Maven coordinates")
    require(pin["bomRef"] == f"pkg:maven/{g}/{a}@{v}?type=jar", "coordinate mismatch")
    url = "https://repo.maven.apache.org/maven2/" + g.replace(".", "/") + f"/{a}/{v}/{a}-{v}-sources.jar"
    raw = fetch(url, 25_000_000)
    expected = fetch(url + ".sha1", 1024).decode("ascii").strip().split()[0].lower()
    require(re.fullmatch(r"[0-9a-f]{40}", expected) and hashlib.sha1(raw).hexdigest() == expected, "published Maven checksum mismatch")
    return {**inspect_source(pin, raw), "url": url, "mavenCentralSha1": expected}

def proposal(row):
    kind = row["engineeringClassification"]
    status = row["evidenceStatus"]
    names = [v.get("expression") or v.get("license", {}).get("id") or v.get("license", {}).get("name")
             for v in row["declaredLicenses"]]
    if kind == "FIRST_PARTY":
        terms, rationale = ["Apache-2.0"], "Repository license; scope and applicable notices still require review."
    elif kind == "PERMISSIVE" and len(names) == 1 and status not in ("MISSING_EXACT_TEXT", "TAGGED_DECLARATION_REVIEW_REQUIRED"):
        terms, rationale = names, "Proposed declared terms only; verify exact text, copyright, notices and any embedded content."
    elif kind == "SOURCE_CORROBORATION":
        terms, rationale = ["Apache-2.0"], "Proposed from exact source-header corroboration, not missing POM metadata; inspect non-Java and binary correspondence."
    elif row["bomRef"].startswith("pkg:maven/ch.qos.logback/logback-"):
        terms, rationale = ["EPL-2.0"], "Proposed election only, based on exact upstream dual-license declaration; reviewer must approve covered-source availability and notices or choose LGPL with its obligations."
    else:
        terms, rationale = [], "No selection proposed until exact terms and scope are reconciled."
    return {"bomRef": row["bomRef"], "nestedJarSha256": row["sha256"],
            "declaredLicenses": row["declaredLicenses"], "engineeringClassification": kind,
            "evidenceStatus": status, "proposedLicenseSelection": terms,
            "selectionRationale": rationale, "obligations": row["obligations"],
            "modificationStatus": "NOT_VERIFIED", "reviewer": None, "reviewedAt": None,
            "approvalReference": None, "fulfillmentEvidenceRefs": [],
            "scopes": {"internalIsolatedStaging": "PENDING_SCOPE_REVIEW",
                       "externalBinaryDistribution": "BLOCKED_PENDING_REVIEW",
                       "sourceDistribution": "BLOCKED_PENDING_REVIEW",
                       "containerImageDistribution": "BLOCKED_PENDING_REVIEW"}}

ROLE_NAMES = ("METADATA", "LOGICAL", "PURGE_PREPARATION", "PLACEMENT", "BACKEND", "GRANT_READ",
              "READINESS", "TRANSFER", "WORKER", "GOVERNANCE", "DISPATCHER")
GATES = {
    "environment": ["isolatedAccountProject", "networkIsolation", "testTenantDataClassification", "serviceOwner"],
    "database": ["dedicatedTargetVersion", "migrationIdentity", "tlsVerifyFull", "effectiveAclAndOwnership",
                 "publicDefaultAcl", "roleMembershipRevocation", "restoreQuarantine"],
    "iam": ["issuerAudience", "jwksOriginFreshnessRotation", "membershipEntitlement", "consumerPolicyBackendBindings", "negativeTests"],
    "storage": ["s3ExactBackendLocationTarget", "ossExactBackendLocationTarget", "credentialReferences",
                "unversionedCreateOnly", "noDeleteMigrateAccountPolicy", "tlsEgressAndDeadlines"],
    "scanner": ["reviewedIntegration", "cleanMaliciousUnavailable", "archiveResourceTimeoutLimits"],
    "events": ["isolatedBroker", "credentialReference", "hardDeadlineDedup", "replayDecision"],
    "network": ["privateTlsIngressLoopback", "firewallDnsEgress", "nonRootIdentity", "regularFileSecretMount"],
    "observabilityRecovery": ["alertsAndOwner", "sloRpoRto", "representativeLoad", "recoveryAndFencing"],
    "release": ["scopeLicenseDisposition", "shadedNativeOsInventory", "signedMainEvidence", "imageDigestIfApplicable"]
}
def staging_template(bindings):
    return {"format": "file-storage-staging-acceptance-preparation-v1", "decision": "NO-GO",
            "candidateEvidence": bindings, "environmentReference": None, "ownerApprovalReference": None,
            "schemaVersion": 23, "dispatcherEnabled": False, "physicalDeleteEnabled": False,
            "gates": {k: {"state": "NOT_SUPPLIED", "ownerReference": None,
                          "evidenceReferences": {item: None for item in fields}} for k, fields in GATES.items()},
            "databaseRuntimeRoles": {r: {"loginEvidenceRef": None, "credentialRefEvidence": None,
                                         "aclVerificationRef": None} for r in ROLE_NAMES},
            "authorization": {k: False for k in ("signing", "publication", "provisioning", "deployment")},
            "limitations": "Preparation record only. Populate a private reviewed copy with evidence references, not secret values. No environment access or authorization inferred."}

def build(target, catalog_path):
    def data(name):
        return (target / name).read_bytes()
    raw = data("staging-distribution-review.json")
    review = read(raw)
    provenance = read(data("staging-provenance.json"))
    require(review["decision"] == "NO-GO", "unexpected distribution decision")
    require(review["artifactSha256"] == provenance["artifactSha256"], "artifact binding")
    require(review["bomSha256"] == provenance["cycloneDxSha256"] == sha(data("bom.json")), "BOM binding")
    require(review["distributionNoticeArchiveSha256"] == sha(data("staging-distribution-notices.zip")), "distribution archive binding")
    require(review["licenseEvidenceSha256"] == provenance["licenseEvidenceSha256"] == sha(data("staging-license-evidence.json")), "license evidence binding")
    rows = {row["bomRef"]: row for row in review["libraries"]}
    require(len(rows) == len(review["libraries"]), "duplicate packaged row")
    inventory = read(data("staging-inventory.json"))
    require(provenance["inventorySha256"] == sha(data("staging-inventory.json")), "inventory hash")
    require({r["path"]: r["sha256"] for r in inventory["libraries"]} ==
            {r["path"]: r["sha256"] for r in rows.values()}, "inventory coverage")
    catalog = read(catalog_path.read_bytes())
    require(catalog["format"] == "file-storage-license-disposition-pins-v1", "catalog format")
    pins = catalog["sourcePins"]
    pending = {ref for ref, r in rows.items() if r["evidenceStatus"] in ("MISSING_EXACT_TEXT", "TAGGED_DECLARATION_REVIEW_REQUIRED")}
    require(len(pins) == len(pending) and {p["bomRef"] for p in pins} == pending, "unresolved component set changed; review pins")
    for pin in pins:
        require(rows[pin["bomRef"]]["supplementalSource"]["sha256"] == pin["sourceSha256"], "source pin disagrees with A18 collector")
    with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
        source_records = dict(zip((p["bomRef"] for p in pins), pool.map(source, pins)))
    standard = catalog["standardTerms"]
    require(standard["repository"] == "spdx/license-list-data" and re.fullmatch(r"[0-9a-f]{40}", standard["commit"]), "standard terms identity")
    terms = []
    with zipfile.ZipFile(target / "staging-license-disposition-evidence.zip", "w", compression=zipfile.ZIP_DEFLATED) as out:
        for doc in standard["documents"]:
            require(doc["id"] in ("Apache-2.0", "EPL-2.0", "LGPL-2.1-only") and doc["path"] == "text/" + doc["id"] + ".txt", "standard terms path")
            url = "https://raw.githubusercontent.com/" + standard["repository"] + "/" + standard["commit"] + "/" + doc["path"]
            content = fetch(url, 100_000)
            require(hashlib.sha1(("blob %d\0" % len(content)).encode() + content).hexdigest() == doc["gitBlobSha"], "standard text Git blob mismatch")
            name = "standard-terms/" + doc["id"] + ".txt"
            out.writestr(name, content)
            terms.append({**doc, "url": url, "sha256": sha(content), "archiveEntry": name,
                          "scope": "STANDARD_TEXT_ONLY_NOT_COMPONENT_LICENSE_PROOF"})
        for i, (ref, record) in enumerate(source_records.items()):
            for j, header in enumerate(record["distinctLicenseHeaders"]):
                name = f"source-header-excerpts/{i:02d}/{j:04d}.txt"
                out.writestr(name, header.pop("text").encode("utf-8"))
                header["archiveEntry"] = name
        out.writestr("source-corroboration.json", dump(source_records))
        out.writestr("standard-terms-index.json", dump(terms))
    bindings = {"artifactSha256": provenance["artifactSha256"], "bomSha256": review["bomSha256"],
                "checkoutCommit": provenance["checkoutCommit"], "workflowRun": provenance["workflowRun"],
                "distributionReviewSha256": sha(raw), "distributionNoticeArchiveSha256": review["distributionNoticeArchiveSha256"],
                "sourceEvidenceArchiveSha256": sha(data("staging-license-disposition-evidence.zip"))}
    report = {"format": "file-storage-license-dispositions-v1", "decision": "NO-GO", "bindings": bindings,
              "pinCatalogSha256": sha(catalog_path.read_bytes()), "components": [proposal(r) for r in rows.values()],
              "graphOnly": review["graphOnly"], "sourceCorroboration": source_records, "standardTerms": terms,
              "limitations": ["Proposals only, not legal approval or source-offer fulfillment.",
                              "Source-header indicators and standard text do not establish binary licensing.",
                              "Native/shaded/OS/image review and actual environment acceptance remain open."]}
    (target / "staging-license-dispositions.json").write_bytes(dump(report))
    (target / "staging-acceptance-preparation.json").write_bytes(dump(staging_template(bindings)))
    print("A19 disposition preparation:", len(rows), "packaged;", len(source_records), "targeted source archives;", len(terms), "standard texts; NO-GO")
    for ref, record in source_records.items():
        print(ref, record["javaFiles"], record["headerCounts"])

def self_test():
    b = io.BytesIO()
    with zipfile.ZipFile(b, "w") as out:
        out.writestr("A.java", "/* Licensed under the Apache License, Version 2.0 */\nclass A {}")
        out.writestr("B.java", 'class B { String x = "Apache License Version 2.0"; }')
        out.writestr("resource.txt", "not reviewed")
    raw = b.getvalue()
    pin = {"sourceSha256": sha(raw), "bomRef": "fixture"}
    result = inspect_source(pin, raw)
    require(result["headerCounts"] == {"APACHE_2_0_INDICATOR": 1, "NO_LEADING_COMMENT": 1}, "header scope")
    require(result["nonJavaEntries"] == ["resource.txt"], "non-Java inventory")
    try:
        inspect_source(pin, raw + b"x")
    except ValueError:
        pass
    else:
        raise AssertionError("tampered source accepted")
    r = proposal({"bomRef": "fixture", "sha256": "a"*64, "declaredLicenses": [{"license": {"id": "MIT"}}],
                  "engineeringClassification": "PERMISSIVE", "evidenceStatus": "MISSING_EXACT_TEXT", "obligations": "review"})
    require(not r["proposedLicenseSelection"] and r["approvalReference"] is None and all("APPROVED" not in x for x in r["scopes"].values()), "missing evidence granted approval")
    template = staging_template({"artifactSha256": "a"*64})
    require(len(template["databaseRuntimeRoles"]) == 11 and not any(template["authorization"].values())
            and not template["dispatcherEnabled"] and not template["physicalDeleteEnabled"]
            and template["decision"] == "NO-GO", "unsafe staging defaults")
    print("A19 disposition/acceptance self-tests: 5 passed")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("target", type=pathlib.Path, nargs="?")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
    elif args.target:
        build(args.target, pathlib.Path(__file__).with_name("staging-license-disposition-pins.json"))
    else:
        parser.error("target or --self-test required")
