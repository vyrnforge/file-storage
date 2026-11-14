#!/usr/bin/env python3
"""Verify documentation-only foundation (not a runtime test)."""
from pathlib import Path
import sys
root = Path(__file__).resolve().parents[1]
required = [
    "README.md", "AGENTS.md", ".gpt/GPT_PROJECT_INSTRUCTIONS.txt",
    "CONTRIBUTING.md", "SECURITY.md", ".gitignore",
    "docs/project/00_CONSTITUTION.md", "docs/project/01_CURRENT_STATE.md",
    "docs/project/02_SCOPE.md", "docs/project/03_ROADMAP.md",
    "docs/project/04_DECISIONS.md", "docs/project/05_REVIEW_CHECKLIST.md",
    "docs/architecture/01_ARCHITECTURE.md",
    "docs/architecture/02_STORAGE_CONTROL_PLANE.md",
    "docs/architecture/03_INTEGRATION.md", "docs/security/THREAT_MODEL.md",
    "backend/README.md", "console/README.md", "sdk/README.md",
    "contracts/README.md", "deployment/README.md", "tests/README.md",
]
missing = [p for p in required if not (root / p).is_file()]
if missing:
    print("Missing foundation files:", ", ".join(missing), file=sys.stderr)
    sys.exit(1)
print(f"Foundation inventory OK ({len(required)} files); no runtime checks performed.")
