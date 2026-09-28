#!/usr/bin/env python3
"""Run explicit export setup/verify, then existing Workspace cutpoints on an owned AVD."""
import json
import os
from pathlib import Path
import subprocess
import sys

serial, destination = sys.argv[1:]
output = Path(destination)
owner = json.loads((output / "owner.json").read_text())
assert owner["serial"] == serial
adb = str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb")
runner = "com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner"
fixture = "com.helix.app.export.SessionExportRecoveryDeviceTest"
for phase in ("setup", "verify"):
    os.kill(owner["pid"], 0)
    result = subprocess.run(
        [adb, "-s", serial, "shell", "am", "instrument", "-w", "-e", "class", fixture,
         "-e", "recoveryPhase", phase, runner],
        text=True, capture_output=True, check=True, timeout=180,
    )
    (output / f"export-{phase}.txt").write_text(result.stdout + result.stderr)
    if phase == "setup":
        assert "process crashed" in result.stdout.lower(), result.stdout
    else:
        assert "OK (1 test)" in result.stdout and "FAILURES!!!" not in result.stdout, result.stdout
subprocess.run(
    [sys.executable, "scripts/debug/2026-09-27/verify-hxa210-recovery-cuts.py", serial, destination],
    check=True, timeout=900,
)
