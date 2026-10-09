#!/usr/bin/env python3
"""HXA-252 follow-up only on run-owned-emulator's new read-only API36 process.

Requires current owner authorization for the existing Qwen profile. Never launch alone
against a daily emulator. Frozen app variants share one Room schema and one test APK.
"""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

serial, output = sys.argv[1:]
output = Path(output)
owner = json.loads((output / "owner.json").read_text())
assert owner["serial"] == serial
os.kill(owner["pid"], 0)
assert json.loads((output / "device-properties.json").read_text())["api"] == "36"
spec = importlib.util.spec_from_file_location("owned_runner", "scripts/run-owned-emulator.py")
owned = importlib.util.module_from_spec(spec)
spec.loader.exec_module(owned)
adb = str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb")
runner = "com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner"
frozen = Path("build/discovery-guidance-20261010")
inventory = (output / "instrumentation.txt").read_text()
model = "Qwen3.8-27B"
providers = re.findall(r"VALIDATION_PROVIDER id=(\S+) protocol=OPENAI_CHAT_COMPLETIONS model=" + re.escape(model), inventory)
if len(providers) != 1:
    raise RuntimeError("Exactly one existing authorized Qwen profile required; no fallback account")
provider = providers[0]
identity = re.findall(r"VALIDATION_ROOM_IDENTITY=(\S+)", inventory)
if len(identity) != 1:
    raise RuntimeError("Initial Room identity missing or ambiguous")


def device(*args, timeout=60):
    os.kill(owner["pid"], 0)
    return subprocess.run([adb, "-s", serial, *args], capture_output=True, text=True,
                          timeout=timeout, check=True).stdout


profile_line = re.search(r"VALIDATION_PROVIDER id=" + re.escape(provider) + r"[^\n]*\n(?:INSTRUMENTATION_STATUS[^\n]*\n)*[^\n]*VALIDATION_LOCAL_PORT=(\d+)", inventory)
if profile_line:
    port = profile_line[1]
    device("reverse", f"tcp:{port}", f"tcp:{port}")

tasks = {
    "manual": "com.helix.app.proot.LiveJobAwaitDeviceTest#realModelWaitsAndCollectsOneOriginalExecution",
    "auto": "com.helix.app.proot.LiveJobAwaitDeviceTest#realModelAutoWaitsAndSettlesOneOriginalExecution",
    "archive": "com.helix.app.eval.LiveDiscoveryArchiveDeviceTest#realModelCreatesOneVerifiedArchive",
}
selected = os.environ.get("HXA252_DISCOVERY_TASKS", "manual,auto,archive").split(",")
if not selected or len(set(selected)) != len(selected) or any(task not in tasks for task in selected):
    raise ValueError("Unknown or duplicate task selection; never silently skip requested tasks")
tasks = {task: tasks[task] for task in selected}
(output / "experiment.json").write_text(json.dumps({
    "tasks": selected, "repeats": 2, "model": model, "providerId": provider,
    "testSha256": hashlib.sha256((output / "test.apk").read_bytes()).hexdigest(),
}, indent=2))
rows = []
for repeat in range(2):
    for task_index, (task, test) in enumerate(tasks.items()):
        variants = ("baseline", "candidate") if (repeat + task_index) % 2 == 0 else ("candidate", "baseline")
        for variant in variants:
            index = len(rows) + 1
            label = f"{index:02d}-{task}-{variant}"
            apk = frozen / f"{variant}.apk"
            device("install", "-r", str(apk), timeout=120)
            room = device("shell", "am", "instrument", "-w", "-e", "class",
                          "com.helix.app.eval.ValidationInventoryDeviceTest#reportInstalledRoomIdentityOnly", runner)
            (output / f"{label}-room.txt").write_text(room)
            if not owned.passed(room) or re.findall(r"VALIDATION_ROOM_IDENTITY=(\S+)", room) != identity:
                raise RuntimeError("Room identity changed; stop before using profile")
            started = time.monotonic()
            result = device("shell", "am", "instrument", "-w", "-e", "class", test,
                            "-e", "liveJob", "true", "-e", "liveDiscovery", "true",
                            "-e", "liveProviderId", provider, "-e", "liveModel", model,
                            runner, timeout=330)
            (output / f"{label}.txt").write_text(result)
            rows.append({"index": index, "task": task, "variant": variant,
                         "passed": owned.passed(result), "seconds": time.monotonic() - started,
                         "appSha256": hashlib.sha256(apk.read_bytes()).hexdigest()})
            (output / "comparison.json").write_text(json.dumps(rows, indent=2))
            if task != "archive":
                diagnostic = device("shell", "am", "instrument", "-w", "-e", "class",
                                    "com.helix.app.eval.ValidationInventoryDeviceTest#reportLatestSyntheticJobFailure", runner)
                (output / f"{label}-audit.txt").write_text(diagnostic)
                if not owned.passed(diagnostic):
                    raise RuntimeError("Synthetic diagnostic failed; evidence incomplete")
            print(f"{label} passed={rows[-1]['passed']} seconds={rows[-1]['seconds']:.2f}", flush=True)
# Product failures are deliberately retained in comparison.json; infrastructure errors raise.
print(json.dumps(rows, indent=2), flush=True)
