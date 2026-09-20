#!/usr/bin/env python3
"""Fetch pinned upstream source for license/behavior inspection only; never vendor it into production."""
import hashlib
import json
from pathlib import Path
import tarfile
import urllib.request

COMMIT = "27e024fccb2d722b47c57f5da1b4da9bca477b68"
EXPECTED_SHA256 = "c0a9a6e2f24e88cace413aa6d9ea9ddabe8d30c43fbf6e449ea86c2e411fddbc"
ROOT = Path(__file__).resolve().parents[3] / "build/hxa197-termlib-0.2.1"
URL = f"https://codeload.github.com/connectbot/termlib/tar.gz/{COMMIT}"
with urllib.request.urlopen(URL, timeout=60) as response:
    data = response.read()
digest = hashlib.sha256(data).hexdigest()
if digest != EXPECTED_SHA256:
    raise SystemExit(f"Pinned source archive changed: {digest}")
ROOT.mkdir(parents=True, exist_ok=True)
archive = ROOT / "upstream.tar.gz"
archive.write_bytes(data)
with tarfile.open(archive) as source:
    source.extractall(ROOT / "upstream", filter="data")
provenance = dict(commit=COMMIT, url=URL, sha256=digest, bytes=len(data))
(ROOT / "upstream-provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
print(json.dumps(provenance, indent=2))
