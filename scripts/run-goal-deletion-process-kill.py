#!/usr/bin/env python3
"""Verify Goal deletion across two actual SIGKILL boundaries on a dedicated emulator."""
import argparse
from android_process_control import kill_emulator_app, verify_emulator_signal_control
import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default=shutil.which("adb"))
    parser.add_argument("--output", type=pathlib.Path, required=True, help="New evidence directory")
    args = parser.parse_args()
    if not args.adb or not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Pass an adb executable and dedicated emulator serial")
    args.output.mkdir(parents=True, exist_ok=False)
    base = [args.adb, "-s", args.serial]
    verify_emulator_signal_control(base)
    records = []
    for phase in ["control", "prepare", "recover-commit", "recover-final"]:
        path = args.output / f"goal-delete-kill-{phase}.log"
        command = base + ["shell", "am", "instrument", "-w", "-r", "-e", "class",
                          "com.helix.app.GoalDeletionProcessKillDeviceTest", "-e",
                          "goal.delete.kill.phase", phase,
                          "com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner"]
        with path.open("w") as output:
            process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT)
            if phase in ("prepare", "recover-commit"):
                match = await_marker(process, path)
                boundary, pid = match.groups()
                expected = "before-commit" if phase == "prepare" else "after-commit"
                assert boundary == expected, (boundary, expected)
                active = subprocess.check_output(base + ["shell", "pidof", "com.helix.agent"], text=True).split()
                assert pid in active, (pid, active)
                kill_emulator_app(base, "com.helix.agent", pid)
                process.wait(timeout=15)
                assert "shortMsg=Process crashed." in path.read_text(), path.read_text()
                record = dict(phase=phase, boundary=boundary, pid=int(pid), signal="SIGKILL", signalAuthority="emulator host su 0", log=path.name)
            else:
                process.wait(timeout=45)
                assert "OK (1 test)" in path.read_text(), path.read_text()
                record = dict(phase=phase, tests=1, log=path.name)
        records.append(record)
        print(json.dumps(record), flush=True)
    root = pathlib.Path(__file__).resolve().parents[1]
    apk_paths = ["app/build/outputs/apk/consumer/debug/app-consumer-debug.apk",
                 "app/build/outputs/apk/androidTest/consumer/debug/app-consumer-debug-androidTest.apk"]
    result = dict(serial=args.serial, api=subprocess.check_output(
        base + ["shell", "getprop", "ro.build.version.sdk"], text=True).strip(), records=records,
        apks={p: hashlib.sha256((root / p).read_bytes()).hexdigest() for p in apk_paths},
        scope="Production deletion coordinator, Room and WorkManager; before and after commit; no model/tool execution")
    (args.output / "goal-delete-kill-result.json").write_text(json.dumps(result, indent=2) + "\n")


def await_marker(process, path):
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        match = re.search(r"GOAL_DELETE_KILL_READY boundary=(\S+) pid=(\d+)", path.read_text())
        if match:
            return match
        if process.poll() is not None:
            raise RuntimeError(f"Instrumentation ended without ready marker: {path}")
        time.sleep(0.05)
    # Do not restart or reset the fixture on an observation timeout.
    raise TimeoutError(f"No ready marker yet; inspect running instrumentation and {path}")


if __name__ == "__main__":
    main()
