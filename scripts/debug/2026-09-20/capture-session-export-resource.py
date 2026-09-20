#!/usr/bin/env python3
"""Collect actual synthetic export resource measurements from the owned storage-test AVD."""
import json
import os
from pathlib import Path
import subprocess
import sys

serial, destination = sys.argv[1:]
output = Path(destination)
owner = json.loads((output / "owner.json").read_text())
if serial != owner["serial"]:
    raise RuntimeError("Unexpected device identity")
os.kill(owner["pid"], 0)
adb = str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb")
identity = subprocess.check_output([adb, "-s", serial, "emu", "avd", "name"], text=True)
if owner["avd"] not in identity.splitlines():
    raise RuntimeError("Unexpected AVD identity")
for name in ("session-export-resource.json", "session-export-snapshot-limit.json", "session-export-query-plans.json"):
    data = subprocess.check_output([adb, "-s", serial, "exec-out", "run-as", "com.helix.core.storage.test",
                                    "cat", "files/" + name], timeout=30)
    report = json.loads(data)
    (output / name).write_bytes(data)
    print(name + ": " + json.dumps(report, sort_keys=True), flush=True)
