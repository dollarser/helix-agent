#!/usr/bin/env python3
"""HXA-198 dual ordinary-application main-death gate; helpers derived from 2026-09-20/run-ordinary-terminal.py."""
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



verify_emulator_signal_control(base)
device("shell", "settings", "put", "secure", "show_ime_with_hard_keyboard", "1")
for name in ("first", "second"):
    script = (f"export RECOVERY_VALUE={name}\n"
              f"printf 1 >> {name}.starts\n"
              f"echo $$ > {name}.pid\n")
    subprocess.run(base + ["shell", "run-as", package, "sh", "-c",
                          shlex.quote("cat > " + workspace + name + ".sh")],
                   input=script, text=True, check=True, timeout=10)
device("shell", "am", "force-stop", package)
launch_terminal()
cold_pid = subprocess.run(base + ["shell", "pidof", package + ":proot"], capture_output=True, text=True, timeout=10)
assert cold_pid.returncode == 1 and not cold_pid.stdout.strip(), "Passive navigation cold-bound the Runtime"
cold_started = time.monotonic()
click("Start session")
wait_for(state, lambda text: "RUNNING" in text)
cold_running_ms = round((time.monotonic() - cold_started) * 1000)
keyboard()
command(". first.sh")
first_pid = wait_for(lambda: read("first.pid"), lambda text: text.isdigit())
cold_shell_roundtrip_ms = round((time.monotonic() - cold_started) * 1000)
device("shell", "input", "keyevent", "4")
click("New terminal")
wait_for(state, lambda text: "Terminal 2 [RUNNING]" in text)
# Keyboard preference remains on across tab changes; focus is acquired by the new viewport.
wait_for(lambda: device("shell", "dumpsys", "input_method"),
         lambda text: "mInputShown=true" in text and "ImeInputView" in text)
command(". second.sh")
second_pid = wait_for(lambda: read("second.pid"), lambda text: text.isdigit())
assert first_pid != second_pid
original = identity()
main_pid = device("shell", "pidof", package)
runtime_pid = device("shell", "pidof", package + ":proot")
boot = device("shell", "cat", "/proc/sys/kernel/random/boot_id")
kill_emulator_app(base, package, main_pid)
assert device("shell", "pidof", package + ":proot") == runtime_pid
assert device("shell", "cat", "/proc/sys/kernel/random/boot_id") == boot
assert identity() == original
launch_terminal()
new_main_pid = device("shell", "pidof", package)
assert new_main_pid.isdigit() and new_main_pid != main_pid
click("Connect")
wait_for(state, lambda text: "Terminal 1 [RUNNING]" in text and "Terminal 2 [RUNNING]" in text)
keyboard()
command('echo "$RECOVERY_VALUE:$$" > first.reconnected')
wait_for(lambda: read("first.reconnected"), lambda text: text == "first:" + first_pid)
device("shell", "input", "keyevent", "4")
click("Terminal 2 [RUNNING]")
wait_for(lambda: device("shell", "dumpsys", "input_method"),
         lambda text: "mInputShown=true" in text and "ImeInputView" in text)
command('echo "$RECOVERY_VALUE:$$" > second.reconnected')
wait_for(lambda: read("second.reconnected"), lambda text: text == "second:" + second_pid)
assert identity() == original
assert read("first.starts") == "1" and read("second.starts") == "1"
device("shell", "input", "keyevent", "4")
# Stop/settle the first while the second remains usable, then release the final owner.
click("Terminal 1 [RUNNING]")
click("Stop session")
click("Settle and close")
wait_for(state, lambda text: "RUNNING" in text and "Terminal 2" not in text)
click("Stop session")
click("Settle and close")
wait_for(state, lambda text: "Start session" in text)
for name in ("manual-terminal", "owner"):
    record = subprocess.check_output(base + ["exec-out", "run-as", package,
        "cat", "files/execution-admission/" + name], timeout=10)
    assert record == b"\x00\x00\x00\x01\x00", "Admission still retained"
(output / "dual-terminal-main-death.json").write_text(json.dumps({
    "mainPid": main_pid, "newMainPid": new_main_pid, "runtimePid": runtime_pid,
    "shellPids": [first_pid, second_pid], "bootId": boot,
    "identityHashes": original, "startsPerShell": [1, 1],
    "bothOriginalShellsReconnected": True, "finalAdmissionReleased": True,
    "coldStartUiRunningMs": cold_running_ms, "coldStartUiShellRoundtripMs": cold_shell_roundtrip_ms,
    "coldStartIncludesHostUiAutomation": True, "runtimeAbsentBeforeStart": True,
}, indent=2))
print("PASS dual-terminal ordinary main-process death, no replay, explicit settlement")
