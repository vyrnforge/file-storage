#!/usr/bin/env python3
"""Guard against accidentally tracking common private inputs. Not a secret scanner."""
import subprocess
import sys
from pathlib import PurePosixPath

paths = subprocess.check_output(["git", "ls-files", "-z"]).decode("utf-8").split("\0")
bad = []
for path in filter(None, paths):
    p = PurePosixPath(path)
    name = p.name.lower()
    if (any(part.lower() in {"legacy", "secrets"} for part in p.parts)
        or (name == ".env" or (name.startswith(".env.") and name != ".env.example"))
        or name in {"src.zip", "application-local.yml", "application-local.yaml", "application-local.properties"}
        or p.suffix.lower() in {".pem", ".key", ".p12", ".jks"}):
        bad.append(path)
if bad:
    print("Prohibited tracked private-input paths:", file=sys.stderr)
    for p in bad:
        print(repr(p), file=sys.stderr)
    sys.exit(1)
print("Repository hygiene OK (path check only; not secret scanning).")
