#!/usr/bin/env python3
"""Bounded follow-up on an owned emulator, restoring display configuration."""
import os
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from importlib.machinery import SourceFileLoader
passed = SourceFileLoader("owned_runner", "scripts/run-owned-emulator.py").load_module().passed
serial, directory = sys.argv[1:]
root = Path(directory)
adb = str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb")
def device(*args):
    return subprocess.run([adb, "-s", serial, *args], text=True, capture_output=True,
                          timeout=300, check=True).stdout

(root / "ime-inventory.txt").write_text(device("shell", "ime", "list", "-s"))
package = os.environ.get("HELIX_DEVICE_PACKAGE", "com.helix.agent")
if package not in ("com.helix.agent", "com.helix.agent.developer"):
    raise ValueError("Specify the installed test target package")
runner = package + ".test/com.helix.app.HelixAndroidJUnitRunner"
device("install", "-r", "runtime/cli-client/build/outputs/apk/androidTest/debug/cli-client-debug-androidTest.apk")
result = device("shell", "am", "instrument", "-w", "-e", "class",
                "com.helix.runtime.cli.client.CliReplayMaintenanceWireDeviceTest",
                "com.helix.runtime.cli.client.test/androidx.test.runner.AndroidJUnitRunner")
(root / "cli-replay.txt").write_text(result)
if not passed(result):
    raise RuntimeError("CLI replay checks failed")
try:
    device("shell", "wm", "size", "840x1800")
    device("shell", "settings", "put", "system", "font_scale", "1.3")
    result = device("shell", "am", "instrument", "-w", "-e", "class",
                    "com.helix.app.ui.ComposerInteractionsDeviceTest,com.helix.app.ui.ProviderSettingsFormDeviceTest",
                    runner)
    (root / "narrow-font.txt").write_text(result)
    if not passed(result):
        capture = subprocess.run(
            [adb, "-s", serial, "exec-out", "run-as", package, "cat",
             "cache/provider-form-failure.png"], capture_output=True, timeout=30, check=True)
        (root / "form-failure.png").write_bytes(capture.stdout)
        (root / "form-geometry-logcat.txt").write_text(
            device("logcat", "-d", "-t", "2000", "-s", "System.out"))
        raise RuntimeError("Narrow/large-font checks failed")
finally:
    device("shell", "wm", "size", "1080x2400")
    device("shell", "settings", "put", "system", "font_scale", "1.0")
result = device("shell", "am", "instrument", "-w", "-e", "class",
                "com.helix.app.ui.PinyinComposerDeviceTest",
                runner)
(root / "pinyin.txt").write_text(result)
preedit = subprocess.run(
    [adb, "-s", serial, "exec-out", "run-as", package, "cat", "cache/pinyin-preedit.png"],
    capture_output=True, timeout=30)
if preedit.returncode == 0:
    (root / "pinyin-preedit.png").write_bytes(preedit.stdout)
if not passed(result):
    capture = subprocess.run(
        [adb, "-s", serial, "exec-out", "run-as", package, "cat", "cache/pinyin-failure.png"],
        capture_output=True, timeout=30)
    if capture.returncode == 0:
        (root / "pinyin-failure.png").write_bytes(capture.stdout)
    raise RuntimeError("Actual pinyin composition/commit failed")
