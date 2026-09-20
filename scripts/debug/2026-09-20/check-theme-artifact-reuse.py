#!/usr/bin/env python3
"""Reuse device evidence only for byte-identical current APKs of the named flavor."""
import hashlib
import json
from pathlib import Path
import sys

prefix, variant = sys.argv[1:]
assert variant in ("consumer", "developer")
paths = {
    "app": Path(f"app/build/outputs/apk/{variant}/debug/app-{variant}-debug.apk"),
    "test": Path(f"app/build/outputs/apk/androidTest/{variant}/debug/app-{variant}-debug-androidTest.apk"),
}
actual = {key: hashlib.sha256(path.read_bytes()).hexdigest() for key, path in paths.items()}
for api in (29, 36):
    recorded = json.loads(Path(f"build/{prefix}-{variant}-api{api}/artifacts.json").read_text())
    assert actual == recorded, f"{variant} API{api} artifacts changed; new device acceptance required"
Path(f"build/{prefix}-{variant}-reuse.json").write_text(json.dumps(actual, indent=2) + "\n")
print(f"Current {variant} APKs are byte-identical to both accepted API artifacts")
