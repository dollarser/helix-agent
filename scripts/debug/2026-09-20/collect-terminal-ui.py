#!/usr/bin/env python3
"""Collect production terminal screenshots while the caller owns the emulator."""
import os
from pathlib import Path
import subprocess
import sys

serial, output = sys.argv[1:]
adb = str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb")
for name in ("terminal-shell.png", "terminal-keyboard.png", "terminal-rotated.png"):
    payload = subprocess.check_output([adb, "-s", serial, "exec-out", "run-as",
        "com.helix.agent.developer", "cat", "cache/terminal-ui/" + name])
    assert payload.startswith(b"\x89PNG\r\n\x1a\n"), "Missing screenshot"
    (Path(output) / name).write_bytes(payload)
