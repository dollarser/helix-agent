#!/usr/bin/env python3
"""Read-only observation plus ordinary app launch on the runner-owned synthetic test device."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

serial, destination = sys.argv[1:]
output = Path(destination)
adb = str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb")
package = "com.helix.agent.developer"


def device(*args):
    return subprocess.check_output([adb, "-s", serial, *args], text=True, timeout=20).strip()


facts = dict(line.split("=", 1) for line in device(
    "shell", "run-as", package, "cat", "no_backup/detached-goal-recovery.properties"
).splitlines() if "=" in line and not line.startswith("#"))
job = facts["job"]
if not re.fullmatch(r"job_[0-9a-f]{12}", job):
    raise RuntimeError("Invalid synthetic job identity")
boot = device("shell", "cat", "/proc/sys/kernel/random/boot_id")
try:
    runtime_pid = device("shell", "pidof", package + ":proot")
except subprocess.CalledProcessError:
    (output / "recovery-process-logcat.txt").write_text(device("logcat", "-d", "-s", "ActivityManager"))
    (output / "recovery-processes.txt").write_text(device("shell", "ps", "-A"))
    raise
if not runtime_pid.isdigit():
    raise RuntimeError("Original Runtime not alive after main-process death")
old_pid = device("shell", "run-as", package, "cat", "no_backup/recovery-device-pid")
device("shell", "am", "start", "-W", "-n", package + "/com.helix.app.MainActivity")
new_pid = device("shell", "pidof", package)
if not new_pid.isdigit() or new_pid == old_pid:
    raise RuntimeError("Ordinary launch did not create a new main process")
observed = []
deadline = time.monotonic() + 35
while time.monotonic() < deadline:
    if device("shell", "pidof", package + ":proot") != runtime_pid:
        raise RuntimeError("Runtime process changed before original Job completed")
    record = json.loads(device("shell", "run-as", package, "cat", f"files/runtime/jobs/{job}/record.json"))
    observed.append(record["state"])
    if record["state"] == "SUCCEEDED":
        break
    if record["state"] not in ("PENDING", "RUNNING"):
        raise RuntimeError("Original Job did not succeed: " + record["state"])
    time.sleep(0.2)
else:
    raise TimeoutError("Original Runtime Job did not complete")
if "RUNNING" not in observed:
    raise RuntimeError("No running Job observed after new main process started")
if device("shell", "cat", "/proc/sys/kernel/random/boot_id") != boot:
    raise RuntimeError("Unexpected device reboot")
(output / "live-host-recovery.json").write_text(json.dumps({
    "job": job, "oldMainPid": old_pid, "newMainPid": new_pid, "runtimePid": runtime_pid,
    "bootId": boot, "states": observed, "terminalCommit": record["terminalCommit"],
}, indent=2))
