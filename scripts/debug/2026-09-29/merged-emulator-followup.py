#!/usr/bin/env python3
"""Bounded recovery, layout and optional owner-authorized SGLang checks."""
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
serial, destination = sys.argv[1:]
output = Path(destination).resolve()
owner = json.loads((output / "owner.json").read_text())
assert owner["serial"] == serial
os.kill(owner["pid"], 0)
package = os.environ["HXA214_PACKAGE"]
assert package in ("com.helix.agent", "com.helix.agent.developer")
adb = [str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb"), "-s", serial]
runner = package + ".test/com.helix.app.HelixAndroidJUnitRunner"

subprocess.run([sys.executable, str(ROOT / "scripts/debug/2026-09-29/merged-composer-process-after.py"),
                serial, str(output)], check=True, cwd=ROOT)

for width in (320, 360, 412):
    subprocess.run(adb + ["shell", "wm", "density", "160"], check=True)
    subprocess.run(adb + ["shell", "wm", "size", f"{width}x900"], check=True)
    subprocess.run(adb + ["shell", "settings", "put", "system", "font_scale", "1.3"], check=True)
    result = subprocess.run(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class",
        "com.helix.app.ui.ProviderSettingsFormDeviceTest,com.helix.app.ui.ProviderContextRecoveryDeviceTest",
        runner], text=True, capture_output=True, timeout=180, check=True)
    (output / f"layout-{width}.txt").write_text(result.stdout + result.stderr)
    assert "OK (4 tests)" in result.stdout and "FAILURES!!!" not in result.stdout, result.stdout

if os.environ.get("HELIX_MERGED_SGLANG") == "true":
    assert package == "com.helix.agent.developer"
    subprocess.run([sys.executable, str(ROOT / "scripts/run-hxa100-provider-evals.py"), serial,
                    "--output", str(output / "sglang-protocols"), "--suite", "providers"],
                   check=True, cwd=ROOT, timeout=900)
