#!/usr/bin/env python3
"""HXA-199 actual-duration lease and detach gate; UI helpers derived from the HXA-198 runner."""
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from android_process_control import kill_emulator_app, verify_emulator_signal_control

serial, destination = sys.argv[1:]
output = Path(destination)
base = [str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb"), "-s", serial]
package = "com.helix.agent.developer"
workspace = "files/workspaces/app/terminal-recovery/"


def device(*args):
    return subprocess.check_output(base + list(args), text=True, timeout=30).strip()


def read(name):
    result = subprocess.run(base + ["shell", "run-as", package, "cat", workspace + name],
                            capture_output=True, text=True, timeout=10)
    if result.returncode and "No such file" not in result.stderr:
        raise RuntimeError(result.stderr)
    return result.stdout.strip()


def wait_for(action, predicate, seconds=30):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = action()
        if predicate(value):
            return value
        time.sleep(.2)
    (output / "terminal-timeout.png").write_bytes(subprocess.check_output(
        base + ["exec-out", "screencap", "-p"], timeout=15))
    (output / "terminal-timeout-logcat.txt").write_text(device("logcat", "-d", "-t", "1000"))
    raise RuntimeError("Timed out: " + repr(value)[-2000:])


def snapshot():
    device("shell", "uiautomator", "dump", "/sdcard/terminal-ui.xml")
    xml = device("shell", "cat", "/sdcard/terminal-ui.xml")
    (output / "ordinary-terminal-ui.xml").write_text(xml)
    return ET.fromstring(xml)


def click(label):
    def attempt():
        tree = snapshot()
        parents = {child: parent for parent in tree.iter() for child in parent}
        for node in tree.iter("node"):
            a = node.attrib
            current = node
            enabled = True
            while current is not None:
                enabled = enabled and current.get("enabled", "true") == "true"
                current = parents.get(current)
            if label in (a.get("text"), a.get("content-desc")) and enabled:
                bounds = list(map(int, re.findall(r"\d+", a["bounds"])))
                device("shell", "input", "tap", str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
                return True
        return False
    wait_for(attempt, bool)


def launch_terminal():
    device("shell", "am", "start", "-W", "-n", package + "/com.helix.app.MainActivity")
    click("App navigation")
    click("Files")
    click("Local files")
    click("terminal-recovery")
    click("Open terminal")


def command(text):
    device("shell", "input", "text", shlex.quote(text.replace(" ", "%s")))
    device("shell", "input", "keyevent", "66")


def keyboard():
    click("Keyboard")
    wait_for(lambda: device("shell", "dumpsys", "input_method"),
             lambda text: "mInputShown=true" in text and "ImeInputView" in text)


def identity():
    values = []
    for name in ("manual-terminal", "owner"):
        values.append(subprocess.check_output(base + ["exec-out", "run-as", package,
            "cat", "files/execution-admission/" + name], timeout=10))
    return [hashlib.sha256(value).hexdigest() for value in values]


def state():
    return " ".join(node.attrib.get("text", "") for node in snapshot().iter("node"))



import io
import struct


def journal(name):
    raw = subprocess.check_output(base + ["exec-out", "run-as", package,
        "cat", "files/terminal-sessions/" + name], timeout=10)
    stream = io.BytesIO(raw)
    def number(fmt):
        return struct.unpack(fmt, stream.read(struct.calcsize(fmt)))[0]
    def text():
        return stream.read(number(">H")).decode("utf-8")
    assert number(">i") == 1
    rec = dict(zip(("sessionId", "generation", "executionId", "workspace", "runtimeGeneration"),
                   [text() for _ in range(5)]))
    rec.update(bootCount=number(">i"), createdAtEpochMs=number(">q"),
               startedAtElapsedMs=number(">q"), deadlineElapsedMs=number(">q"), phase=text())
    if number(">?"):
        rec.update(pid=number(">i"), startTicks=number(">q"))
    rec.update(stopReason=text(), stopProof=text(), exitStatus=number(">i"),
               reconciled=number(">?"), stoppedAtBootCount=number(">i"))
    assert stream.read() == b""
    return rec


def now_ms():
    return int(float(device("shell", "cat", "/proc/uptime").split()[0]) * 1000)


def alive(pid):
    result = subprocess.run(base + ["shell", "su", "0", "test", "-d", f"/proc/{pid}"], timeout=10)
    return result.returncode == 0


def resources(pid):
    result = {}
    for label, directory in (("fd", "fd"), ("threads", "task")):
        result[label] = len(device("shell", "su", "0", "ls", f"/proc/{pid}/{directory}").splitlines())
    return result


verify_emulator_signal_control(base)
device("shell", "settings", "put", "secure", "show_ime_with_hard_keyboard", "1")
for name in ("lease", "idle"):
    script = f"echo $$ > {name}.shell; sh -c 'echo $$ > {name}.child; exec sleep 9000'\n"
    subprocess.run(base + ["shell", "run-as", package, "sh", "-c",
                          shlex.quote("cat > " + workspace + name + ".sh")],
                   input=script, text=True, check=True, timeout=10)
launch_terminal()
click("Start session")
wait_for(state, lambda text: "RUNNING" in text)
keyboard()
command(". lease.sh")
wait_for(lambda: read("lease.child"), lambda text: text.isdigit())
device("shell", "input", "keyevent", "4")
click("New terminal")
wait_for(state, lambda text: "Terminal 2 [RUNNING]" in text)
wait_for(lambda: device("shell", "dumpsys", "input_method"),
         lambda text: "mInputShown=true" in text and "ImeInputView" in text)
command(". idle.sh")
wait_for(lambda: read("idle.child"), lambda text: text.isdigit())
device("shell", "input", "keyevent", "4")
detach_before = now_ms()
click("Terminal 1 [RUNNING]")
detach_after = now_ms()
names = device("shell", "run-as", package, "ls", "files/terminal-sessions").splitlines()
names = sorted((n for n in names if n.endswith(".pty")), key=lambda n: journal(n)["startedAtElapsedMs"])
assert len(names) == 2
initial = [journal(n) for n in names]
assert all(r["deadlineElapsedMs"] - r["startedAtElapsedMs"] == 7_200_000 for r in initial)
runtime_pid = device("shell", "pidof", package + ":proot")
child_pids = {n: int(read(n + ".child")) for n in ("lease", "idle")}
shell_pids = {n: int(read(n + ".shell")) for n in ("lease", "idle")}
start_resources = resources(runtime_pid)
identity_before = identity()
metadata = {"initial": initial, "detachWindowMs": [detach_before, detach_after],
            "runtimePid": runtime_pid, "childPids": child_pids, "shellPids": shell_pids,
            "resourcesStart": start_resources, "api": device("shell", "getprop", "ro.build.version.sdk"),
            "pageSize": device("shell", "getconf", "PAGESIZE"), "physical": False}
(output / "long-duration-start.json").write_text(json.dumps(metadata, indent=2))
observed = {}
previous_running = {"lease": now_ms(), "idle": now_ms()}
while len(observed) < 2:
    current_ms = now_ms()
    assert current_ms <= initial[0]["deadlineElapsedMs"] + 60_000, "Lease settlement deadline exceeded"
    assert device("shell", "pidof", package + ":proot") == runtime_pid, "Runtime died during lease gate"
    records = [journal(n) for n in names]
    for label, rec in zip(("lease", "idle"), records):
        if label in observed:
            continue
        if rec["phase"] == "RUNNING":
            previous_running[label] = current_ms
            assert alive(child_pids[label]) and alive(shell_pids[label])
            if label == "idle":
                assert current_ms <= detach_after + 1_800_000 + 60_000, "Detached session did not expire"
        elif rec["phase"] == "STOPPED":
            expected = "LEASE_EXPIRED" if label == "lease" else "IDLE"
            assert rec["stopReason"] == expected and rec["stopProof"] == "PROCESS_TREE_EXIT"
            threshold = initial[0]["deadlineElapsedMs"] if label == "lease" else detach_before + 1_800_000
            assert current_ms >= threshold, "Session stopped before its full configured interval"
            assert not alive(child_pids[label]) and not alive(shell_pids[label])
            observed[label] = {"record": rec, "lastRunningMs": previous_running[label], "observedStoppedMs": current_ms}
            print(json.dumps({"completed": label, **observed[label]}), flush=True)
        else:
            assert rec["phase"] == "CLOSING", f"Unexpected phase {rec}"
    sample = {"uptimeMs": current_ms, "phases": [r["phase"] for r in records],
              "resources": resources(runtime_pid)}
    with (output / "long-duration-samples.jsonl").open("a") as log:
        log.write(json.dumps(sample) + "\n")
    (output / "long-duration-progress.json").write_text(json.dumps({"sample": sample, "completed": observed}, indent=2))
    time.sleep(10)
assert identity() == identity_before, "Stop proof must not silently release admission"
end_resources = resources(runtime_pid)
click("Settle and close")
click("Settle and close")
wait_for(state, lambda text: "Start session" in text)
(output / "long-duration-result.json").write_text(json.dumps({
    **metadata, "observed": observed, "resourcesEnd": end_resources,
    "status": "passed", "explicitSettlement": True,
    "test_method": "actualDefaultLeaseAndDetachIdle", "test_class": "OrdinaryTerminalDurationJourney",
    "duration_seconds": (observed["lease"]["observedStoppedMs"] - initial[0]["startedAtElapsedMs"]) / 1000,
}, indent=2))
print("PASS actual two-hour lease and thirty-minute detach idle", flush=True)
