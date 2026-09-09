#!/usr/bin/env python3
"""Verify every recovered snapshot and its manifest without executing old scripts."""
import ast
import hashlib
import json
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
records = json.loads((root / "archive-manifest.json").read_text())
paths = set()
for record in records:
    path = root / record["archive"]
    assert path.resolve().is_relative_to(root)
    assert path not in paths
    paths.add(path)
    assert re.fullmatch(r"\d{4}-\d{2}-\d{2}", record["date_from_mtime"])
    assert hashlib.sha256(path.read_bytes()).hexdigest() == record["archive_sha256"]
    assert not re.search(r"/Users/[A-Za-z0-9_-]+/", path.read_text())
for path in root.rglob("*.py"):
    ast.parse(path.read_text(), filename=str(path))
print(f"Verified {len(paths)} historical snapshots and active Python syntax")
