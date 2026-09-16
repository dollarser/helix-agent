"""Preserve initial untracked debugging material outside the source commit.

Uses the takeover inventory captured before editing; never moves tracked files
or newly created scripts. The manifest records relative paths and hashes only.
"""
from pathlib import Path
import hashlib
import json
import subprocess

root = Path(__file__).resolve().parents[3]
evidence = root / "build/wip-takeover-20260916"
archive = evidence / "historical-debug"
inventory = root / "scripts/debug/archive/2026-09-16-wip-inventory.json"
rows = []
for line in (evidence / "initial-status.txt").read_text().splitlines():
    if not line.startswith("?? scripts/debug/"):
        continue
    relative = line[3:]
    source = root / relative
    assert source.is_file(), relative
    tracked = subprocess.run(
        ["git", "ls-files", "--error-unmatch", "--", relative],
        cwd=root, capture_output=True,
    )
    assert tracked.returncode == 1, relative
    destination = archive / relative
    assert not destination.exists(), relative
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    destination.parent.mkdir(parents=True, exist_ok=True)
    source.rename(destination)
    assert hashlib.sha256(destination.read_bytes()).hexdigest() == digest
    rows.append({"original_path": relative, "sha256": digest})
inventory.parent.mkdir(parents=True, exist_ok=True)
inventory.write_text(json.dumps({
    "baseline": "0422c78b",
    "provenance": "Untracked debug scripts and raw logs from the 2026-09-14 to 2026-09-16 WIP sessions",
    "archive": "build/wip-takeover-20260916/historical-debug/",
    "purpose": "Historical evidence only; not executable against the current source tree",
    "files": rows,
}, indent=2) + "\n")
print(f"Preserved {len(rows)} files; hashes recorded in {inventory.relative_to(root)}")
