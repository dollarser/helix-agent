#!/usr/bin/env python3
"""Archive explicitly selected inactive Helix scripts; redact host paths and device IDs."""
import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("sources", nargs="+")
parser.add_argument("--redact-serial", action="append", default=[])
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
manifest = root / "archive-manifest.json"
records = json.loads(manifest.read_text()) if manifest.exists() else []
sources = list(map(Path, args.sources))
processes = subprocess.check_output(["ps", "-axo", "pid,command"], text=True)
for source in sources:
    # Caller verifies enclosing task is inactive; also reject running interpreters using this source.
    if re.search(r"(?:python\S*|bash|zsh|sh)\s+" + re.escape(str(source)), processes):
        raise RuntimeError(f"Source is in use: {source.name}")
for source in sources:
    raw = source.read_bytes()
    day = datetime.fromtimestamp(source.stat().st_mtime).strftime("%Y-%m-%d")
    text = raw.decode()
    text = re.sub(r"/Users/[^/'\"\s]+/Library/Android/sdk", "<ANDROID_SDK>", text)
    text = re.sub(r"/Users/[^/'\"\s]+/Helix[^/'\"\s]*", "<HELIX_CHECKOUT>", text)
    text = re.sub(r"emulator-\d+", "<OWNED_EMULATOR_SERIAL>", text)
    for serial in args.redact_serial:
        text = text.replace(serial, "<EXPLICIT_PHYSICAL_DEVICE_SERIAL>")
    destination = root / day / (source.name + ".txt")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("x") as out:
        out.write(text)
    assert destination.read_text() == text
    records.append({"source_basename": source.name, "date_from_mtime": day,
                    "original_sha256": hashlib.sha256(raw).hexdigest(),
                    "archive": str(destination.relative_to(root)),
                    "archive_sha256": hashlib.sha256(destination.read_bytes()).hexdigest(),
                    "redaction": "host checkout/SDK paths and physical/emulator serials"})
temporary_manifest = manifest.with_suffix(".tmp")
temporary_manifest.write_text(json.dumps(records, indent=2) + "\n")
temporary_manifest.replace(manifest)
# Archive and provenance are durable before removing explicitly supplied inactive originals.
for source in sources:
    source.unlink()
