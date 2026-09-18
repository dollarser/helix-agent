#!/usr/bin/env python3
"""HXA-207 four-quadrant device acceptance for ExtensionJourneyDeviceTest.

Per quadrant (consumer/developer x API 29/36) two owned-emulator runs on the task-assigned
ADB console ports 5690/5692/5694/5696 (never another session's serial; a still-occupied port is
refused):
  - matrix:  the whole class in one instrumentation (12 tests; the 2 restart methods skip via
             assumeTrue because no extensionJourneyPhase argument is passed, and on the consumer
             build the 5 developer-lane MCP tests skip because ADVANCED is unavailable there).
  - restart: seed -> force-stop -> recover (two instrumentations, a NEW process) to exercise the
             "actual scope after restart" scenario end to end.

Every run goes through run-owned-emulator-207.py (a copy of the 2026-09-09 runner whose port cap
was raised to 5750 so the assigned 5690-5696 ports are admitted). Run one at a time through the
with-host-slot.py wrapper. Use --only <flavor>-<api>-<phase> to re-run a single flaked case.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

root = Path(__file__).resolve().parents[3]
runner = root / "scripts/debug/2026-09-18/run-owned-emulator-207.py"
adb = Path(os.environ["ANDROID_HOME"]) / "platform-tools" / "adb"
# The four task-assigned ADB console ports (emulator-<port>). Even, and above the range other
# parallel sessions use, so they are collision-free by reservation.
PORTS = {("consumer", 29): 5690, ("consumer", 36): 5692, ("developer", 29): 5694, ("developer", 36): 5696}
QUADRANTS = [(flavor, api) for flavor in ("consumer", "developer") for api in (29, 36)]
CLASS = "com.helix.app.ExtensionJourneyDeviceTest"


def wait_free(serial, timeout=90):
    """Refuse to launch until the serial is free; if it persists, another owner has it -> raise."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        devices = subprocess.run([str(adb), "devices"], text=True, capture_output=True, check=True).stdout
        if not any(line.split()[0] == serial for line in devices.splitlines()[1:] if line.split()):
            return
        time.sleep(3)
    raise RuntimeError(f"Refusing {serial}: still occupied after {timeout}s (another session owns it)")


def base_cmd(flavor, api):
    package = "com.helix.agent" + (".developer" if flavor == "developer" else "")
    return [
        sys.executable, str(runner),
        "--avd", f"HelixApkUpgrade_API{api}_20260918",
        "--memory-mb", "4096", "--cores", "4",
        "--apk", f"app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk",
        "--test-apk", f"app/build/outputs/apk/androidTest/{flavor}/debug/app-{flavor}-debug-androidTest.apk",
        "--runner", f"{package}.test/com.helix.app.HelixAndroidJUnitRunner",
        "--timeout", "600",
        # Hermetic: the InAppMcpServer fixture is on loopback (unaffected); no real network is used.
        "--airplane-mode",
    ]


def run_case(flavor, api, phase, only):
    name = f"{flavor}-{api}-{phase}"
    if only is not None and name not in only:
        return
    port = PORTS[(flavor, api)]
    serial = f"emulator-{port}"
    wait_free(serial)
    output = f"build/hxa207-device/{name}"
    package = "com.helix.agent" + (".developer" if flavor == "developer" else "")
    env = dict(os.environ)
    if phase == "matrix":
        extra = ["--classes", CLASS]
    else:  # restart: seed is the main run; recover runs in the after-script after a force-stop.
        extra = [
            "--classes", CLASS + "#seedExtensionJourneyScope",
            "--instrument-arg", "extensionJourneyPhase=seed",
            "--after-script", "scripts/debug/2026-09-18/hxa207-restart-recover.py",
        ]
        env["HXA207_RUNNER"] = f"{package}.test/com.helix.app.HelixAndroidJUnitRunner"
        env["HXA207_APP_PACKAGE"] = package
    out_path = root / output
    if out_path.exists():  # explicit re-run of one case: fresh evidence for that case only.
        shutil.rmtree(out_path)
    print(f"RUN {name} on {serial}", flush=True)
    subprocess.run(base_cmd(flavor, api) + ["--port", str(port), "--output", output] + extra,
                   cwd=root, env=env, check=True)


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--only",
                    help="comma-separated case names to run, e.g. consumer-29-matrix,developer-36-restart")
args = parser.parse_args()
only = set(args.only.split(",")) if args.only else None

# All matrices first, then all restarts: by the time a port is reused for its restart, its matrix
# serial has been gone for three other runs, so ADB's delayed serial removal cannot trip us up.
for flavor, api in QUADRANTS:
    run_case(flavor, api, "matrix", only)
for flavor, api in QUADRANTS:
    run_case(flavor, api, "restart", only)
print("HXA-207 device matrix complete", flush=True)
