#!/usr/bin/env python3
"""Check verified GitHub CLI statements against exact subject and CycloneDX predicate.
Cryptographic certificate/source verification MUST already have succeeded in gh.
"""
import hashlib
import json
import pathlib
import sys

def check(entries, kind, jar_digest, bom=None):
    if not isinstance(entries, list) or not entries:
        raise ValueError("no verified attestations")
    for entry in entries:
        statement = entry["verificationResult"]["statement"]
        if statement.get("predicateType") != kind:
            continue
        subjects = statement.get("subject", [])
        if len(subjects) != 1 or subjects[0].get("digest", {}).get("sha256") != jar_digest:
            continue
        if bom is not None and statement.get("predicate") != bom:
            continue
        if not isinstance(statement.get("predicate"), dict):
            continue
        return
    raise ValueError("no verified attestation matches exact subject and predicate")

def self_test():
    import copy
    digest = "a" * 64
    kind = "https://cyclonedx.org/bom"
    bom = {"bomFormat": "CycloneDX", "components": [{"name": "example"}]}
    good = [{"verificationResult": {"statement": {"predicateType": kind,
            "subject": [{"digest": {"sha256": digest}}], "predicate": bom}}}]
    check(good, kind, digest, bom)
    cases = [[], copy.deepcopy(good), copy.deepcopy(good), copy.deepcopy(good), copy.deepcopy(good)]
    cases[1][0]["verificationResult"]["statement"]["subject"][0]["digest"]["sha256"] = "b" * 64
    cases[2][0]["verificationResult"]["statement"]["predicate"] = {"bomFormat": "CycloneDX"}
    cases[3][0]["verificationResult"]["statement"]["predicateType"] = "wrong"
    cases[4][0]["verificationResult"]["statement"]["subject"].append({"digest": {"sha256": digest}})
    for case in cases:
        try:
            check(case, kind, digest, bom)
        except ValueError:
            continue
        raise AssertionError("invalid attestation accepted")
    print("A18 attestation statement checks: 6 passed")

if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    else:
        jar, bom, provenance, sbom = map(pathlib.Path, sys.argv[1:])
        digest = hashlib.sha256(jar.read_bytes()).hexdigest()
        check(json.loads(provenance.read_bytes()), "https://slsa.dev/provenance/v1", digest)
        check(json.loads(sbom.read_bytes()), "https://cyclonedx.org/bom", digest, json.loads(bom.read_bytes()))
        print("Verified statements match exact JAR and CycloneDX predicate")
