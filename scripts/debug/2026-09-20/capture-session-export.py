#!/usr/bin/env python3
"""Read only the synthetic storage-test export from this runner's owned emulator."""
import json
import os
from pathlib import Path
import subprocess
import sys

serial, output_arg = sys.argv[1:]
output = Path(output_arg)
owner = json.loads((output / "owner.json").read_text())
if serial != owner["serial"]:
    raise RuntimeError("Unexpected device identity")
os.kill(owner["pid"], 0)
adb = str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb")
identity = subprocess.check_output([adb, "-s", serial, "emu", "avd", "name"], text=True)
if owner["avd"] not in identity.splitlines():
    raise RuntimeError("Unexpected AVD identity")
artifact = subprocess.check_output([adb, "-s", serial, "exec-out", "run-as", "com.helix.core.storage.test",
                                    "cat", "files/session-export-synthetic.jsonl"], timeout=30)
target = output / "synthetic.jsonl"
target.write_bytes(artifact)
root = Path(__file__).resolve().parents[3]
subprocess.run([sys.executable, str(root / "scripts/validate-session-export.py"), str(target),
                "--messages", str(output / "reconstructed-messages.jsonl"),
                "--calls", str(output / "reconstructed-calls.jsonl")], check=True, timeout=30)
