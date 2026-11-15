#!/usr/bin/env python3
"""Reject minified/packed Maven XML; use an XML editor for full formatting."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NAMESPACE = "{http://maven.apache.org/POM/4.0.0}"

def problems(text):
    errors = []
    try:
        root = ET.fromstring(text)
        if root.tag != NAMESPACE + "project":
            errors.append("expected Maven project root")
    except ET.ParseError as exc:
        return [f"invalid XML: {exc}"]
    if not text.endswith("\n") or "\r" in text:
        errors.append("use LF and a final newline")
    for number, line in enumerate(text.splitlines(), 1):
        # A simple text element may share a line with its own closing tag.
        # Adjacent XML tags (nested elements or siblings) must be expanded.
        # Ignore comments, CDATA and processing instructions in this heuristic.
        if re.search(r">\s*<", line) and not re.search(r"<!--|<!\[CDATA\[|<\?", line):
            errors.append(f"line {number}: put adjacent XML tags on separate lines")
        indent = line[:len(line) - len(line.lstrip(" \t"))]
        if "\t" in indent:
            errors.append(f"line {number}: use spaces, not tabs")
    return errors

def self_test():
    compact = '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion></project>\n'
    readable = '<project xmlns="http://maven.apache.org/POM/4.0.0">\n    <modelVersion>4.0.0</modelVersion>\n</project>\n'
    assert problems(compact), "guard must reject a single-line POM"
    assert not problems(readable), "guard must accept readable XML"
    assert problems("<project>"), "guard must reject malformed XML"

if __name__ == "__main__":
    self_test()
    paths = sorted(p for p in (ROOT / "backend").rglob("pom.xml") if "target" not in p.relative_to(ROOT).parts)
    if not paths:
        sys.exit("No backend POM files found")
    failed = False
    for path in paths:
        # read_bytes preserves CRLF for the line-ending check
        for error in problems(path.read_bytes().decode("utf-8")):
            print(f"{path.relative_to(ROOT)}: {error}", file=sys.stderr)
            failed = True
    if failed:
        sys.exit(1)
    print(f"Maven POM readability OK ({len(paths)} files; guard self-tests passed)")
