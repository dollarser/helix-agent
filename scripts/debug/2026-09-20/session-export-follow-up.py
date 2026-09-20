#!/usr/bin/env python3
"""Run export journeys after real process-restart verification on the same owned emulator."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys


def main():
    serial, destination = sys.argv[1:]
    output = Path(destination)
    owner = json.loads((output / "owner.json").read_text())
    if owner["serial"] != serial:
        raise RuntimeError("Owned serial mismatch")
    os.kill(owner["pid"], 0)
    runner = os.environ["HXA211_RUNNER"]
    allowed = {f"com.helix.agent{suffix}.test/com.helix.app.HelixAndroidJUnitRunner"
               for suffix in ("", ".developer")}
    if runner not in allowed:
        raise RuntimeError("Unexpected test runner")
    adb = str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb")
    identity = subprocess.check_output([adb, "-s", serial, "emu", "avd", "name"], text=True)
    if owner["avd"] not in identity.splitlines():
        raise RuntimeError("Owned AVD identity mismatch")
    classes = ("com.helix.app.export.SessionExportJourneyDeviceTest,com.helix.app.ui.SessionExportUiDeviceTest,"
               "com.helix.app.ui.SessionExportPickerDeviceTest")
    result = subprocess.run([adb, "-s", serial, "shell", "am", "instrument", "-w", "-e", "class", classes, runner],
                            capture_output=True, text=True, timeout=180, check=True)
    (output / "journey-instrumentation.txt").write_text(result.stdout)
    log = subprocess.check_output([adb, "-s", serial, "logcat", "-d", "-t", "20000", "-s", "TestRunner"], text=True)
    (output / "journey-logcat.txt").write_text(log)
    print(result.stdout, flush=True)
    if not re.search(r"^OK \(7 tests\)", result.stdout, re.M) or "FAILURES!!!" in result.stdout:
        raise RuntimeError("Export journeys did not pass all seven tests")
    narrow = Path(__file__).with_name("run-session-export-narrow.py")
    environment = dict(os.environ, HXA211_PACKAGE=runner.split("/", 1)[0].removesuffix(".test"))
    subprocess.run([sys.executable, str(narrow), serial, str(output)], env=environment, check=True, timeout=120)


if __name__ == "__main__":
    main()
