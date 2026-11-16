#!/usr/bin/env python3
"""Deterministic inventory. Text heuristics are review signals, not defect or coverage counts."""
from pathlib import Path
import hashlib
import json
import re
root = Path(__file__).resolve().parents[1]
backend = root / "backend"
rows = []
for path in sorted(backend.rglob("*")):
    if not path.is_file() or "target" in path.relative_to(backend).parts:
        continue
    if path.suffix not in (".java", ".sql") and path.name != "pom.xml":
        continue
    raw = path.read_bytes()
    text = raw.decode("utf-8")
    rel = path.relative_to(root).as_posix()
    lines = text.splitlines()
    row = {"path": rel, "bytes": len(raw), "lines": len(lines),
           "sha256": hashlib.sha256(raw).hexdigest()}
    if path.suffix == ".java":
        row.update({"kind": "test" if "/src/test/" in rel else "production",
                    "longLinesOver120": sum(len(line) > 120 for line in lines),
                    "wildcardImports": len(re.findall(r"^\s*import\s+[^;]+\.\*;", text, re.M)),
                    "preparedStatementCalls": len(re.findall(r"\.prepareStatement\(", text)),
                    "queryTimeoutCalls": len(re.findall(r"\.setQueryTimeout\(", text)),
                    "runtimeExceptionCatchSignals": len(re.findall(r"catch\s*\([^)]*RuntimeException", text)),
                    "todoSignals": len(re.findall(r"\b(?:TODO|FIXME|XXX)\b", text))})
    elif path.suffix == ".sql":
        row["kind"] = "migration"
        row["securityDefinerSignals"] = len(re.findall(r"SECURITY\s+DEFINER", text, re.I))
    else:
        row["kind"] = "pom"
    rows.append(row)
print(json.dumps({"format": "backend-quality-inventory-v1",
                  "limitations": "Text counts are heuristic; no coverage, complexity or vulnerability conclusion.",
                  "files": rows}, indent=2))
