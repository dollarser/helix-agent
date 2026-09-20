#!/usr/bin/env python3
"""Collect synthetic DocumentsUI readback and screenshots from the owned emulator."""
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
package = os.environ.get("HXA211_PACKAGE", "com.helix.agent")
if package not in ("com.helix.agent", "com.helix.agent.developer"):
    raise RuntimeError("Unexpected fixture package")
for name in ("session-export-dialog.png", "session-export-picker.png", "session-export-completed.png",
             "session-export-picker.jsonl"):
    data = subprocess.check_output([adb, "-s", serial, "exec-out", "run-as", package, "cat", "files/" + name], timeout=30)
    (output / name).write_bytes(data)
root = Path(__file__).resolve().parents[3]
subprocess.run([sys.executable, str(root / "scripts/validate-session-export.py"),
                str(output / "session-export-picker.jsonl")], check=True, timeout=30)
