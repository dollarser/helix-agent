#!/usr/bin/env python3
"""Record the actual non-ignored source inputs, including uncommitted acceptance fixes."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[3]
target = Path(sys.argv[1])
if target.exists():
    raise ValueError("Use a new snapshot path")
names = subprocess.check_output(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=root)
files = {}
for name in sorted(set(names.decode().split("\0")) - {""}):
    path = root / name
    if path.is_file():
        files[name] = hashlib.sha256(path.read_bytes()).hexdigest()
    elif path.exists():
        raise ValueError(f"Unsupported source entry: {name}")
canonical = json.dumps(files, sort_keys=True, separators=(",", ":")).encode()
result = {
    "head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
    "source_sha256": hashlib.sha256(canonical).hexdigest(),
    "files": files,
    "scope": "non-ignored source inputs; generated APKs and locked runtime assets verified separately",
}
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(json.dumps(result, indent=2) + "\n")
print(result["source_sha256"])
