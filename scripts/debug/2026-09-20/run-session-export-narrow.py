#!/usr/bin/env python3
"""After a real picker journey, repeat on the same owned AVD at 320dp width and 150% text."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

serial, destination = sys.argv[1:]
output = Path(destination)
owner = json.loads((output / "owner.json").read_text())
if owner["serial"] != serial:
    raise RuntimeError("Unexpected serial")
os.kill(owner["pid"], 0)
root = Path(__file__).resolve().parents[3]
capture = root / "scripts/debug/2026-09-20/capture-session-export-picker.py"
subprocess.run([sys.executable, str(capture), serial, str(output)], check=True)
adb = str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb")
package = os.environ.get("HXA211_PACKAGE", "com.helix.agent")
if package not in ("com.helix.agent", "com.helix.agent.developer"):
    raise RuntimeError("Unexpected package")
narrow = output / "narrow"
narrow.mkdir()
shutil.copyfile(output / "owner.json", narrow / "owner.json")


def shell(*arguments):
    os.kill(owner["pid"], 0)
    return subprocess.check_output([adb, "-s", serial, "shell", *arguments], text=True, timeout=90)


try:
    shell("wm", "size", "840x1680")
    shell("wm", "density", "420")
    shell("settings", "put", "system", "font_scale", "1.5")
    (narrow / "configuration.txt").write_text(shell("wm", "size") + shell("wm", "density") +
                                              "font_scale=" + shell("settings", "get", "system", "font_scale"))
    result = shell("am", "instrument", "-w", "-e", "class", "com.helix.app.ui.SessionExportPickerDeviceTest",
                   "-e", "expectedNarrow", "true", package + ".test/com.helix.app.HelixAndroidJUnitRunner")
    (narrow / "instrumentation.txt").write_text(result)
    if "OK (1 test)" not in result or "FAILURES!!!" in result:
        raise RuntimeError("Narrow picker journey failed")
    subprocess.run([sys.executable, str(capture), serial, str(narrow)], check=True)
finally:
    shell("settings", "put", "system", "font_scale", "1.0")
    shell("wm", "size", "1080x2400")
