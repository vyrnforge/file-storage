# Contributing

Phase 1: follow the current authorized increment in AGENTS.md. Read AGENTS.md and project constitution before work. Use focused branches and reviewable PRs. Keep docs, decisions and backlog consistent. Run `make ci` from the repo root. Do not import private legacy artifacts, introduce secrets, start unapproved runtime work, publish packages, deploy or merge without owner approval.

After approval add reproducible builds, contract and negative security tests, dependency/security scans, and local/hosted CI parity. Target stable check name ci. VyrnRail governance is a reference; provider controls must be independently applied and verified. Active branch rules and CI merge gate verified; see repository security evidence.

## Maven XML style
Keep every pom.xml human-readable: UTF-8, LF, four spaces per indentation level, one element per line (opening and closing tags for a simple text value may share a line). Expand nested elements and dependency/plugin entries over multiple lines. Keep the final newline. Use editor XML formatting rather than minifying XML. Run `python3 scripts/check-pom-format.py` or `make ci` before opening a PR. The lightweight check rejects dense adjacent tags and invalid XML; it is not a full XML formatter.
