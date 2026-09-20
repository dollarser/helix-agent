#!/usr/bin/env python3
"""Exercise the ordinary terminal UI, main death, Runtime death and reboot reconciliation."""
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
        for node in snapshot().iter("node"):
            a = node.attrib
            if label in (a.get("text"), a.get("content-desc")) and a.get("enabled") == "true":
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
launch_terminal()
click("Start session")
wait_for(state, lambda text: "RUNNING" in text)
keyboard()
command(". recover.sh")
wait_for(lambda: read("phase"), lambda text: text == "running")
original = identity()
main_pid = device("shell", "pidof", package)
runtime_pid = device("shell", "pidof", package + ":proot")
boot = device("shell", "cat", "/proc/sys/kernel/random/boot_id")
shell_pid = read("shell.pid")
kill_emulator_app(base, package, main_pid)
wait_for(lambda: read("phase"), lambda text: text == "done")
assert device("shell", "pidof", package + ":proot") == runtime_pid
assert device("shell", "cat", "/proc/sys/kernel/random/boot_id") == boot
assert read("starts") == "1" and identity() == original
launch_terminal()
new_main_pid = device("shell", "pidof", package)
assert new_main_pid != main_pid
click("Connect")
wait_for(state, lambda text: "RUNNING" in text)
keyboard()
command('echo "$RECOVERY_VALUE:$$" > reconnected')
wait_for(lambda: read("reconnected"), lambda text: text == "original:" + shell_pid)
# Kill precisely the observed private Runtime process, never the package or unrelated PIDs.
assert runtime_pid.isdigit() and int(runtime_pid) > 1
assert device("shell", "pidof", package + ":proot") == runtime_pid
device("shell", "su", "0", "kill", "-9", runtime_pid)
device("shell", "input", "keyevent", "4")  # Hide IME; retain the page.
click("Query status")
wait_for(state, lambda text: "UNKNOWN" in text)
assert identity() == original and read("starts") == "1"
tree = snapshot()
assert any(n.attrib.get("clickable") == "true" and n.attrib.get("enabled") == "false"
           and any(child.attrib.get("text") == "Settle and close" for child in n.iter("node"))
           for n in tree.iter("node")), "Unknown same-boot execution must not settle"
device("reboot")
subprocess.run(base + ["wait-for-device"], check=True, timeout=120)
wait_for(lambda: device("shell", "getprop", "sys.boot_completed"), lambda text: text == "1", 120)
device("shell", "input", "keyevent", "82")
new_boot = device("shell", "cat", "/proc/sys/kernel/random/boot_id")
assert new_boot != boot
launch_terminal()
click("Query status")
wait_for(state, lambda text: "UNKNOWN" in text)
assert identity() == original and read("starts") == "1"
click("Settle and close")
wait_for(state, lambda text: "Start session" in text)
for name in ("manual-terminal", "owner"):
    record = subprocess.check_output(base + ["exec-out", "run-as", package,
        "cat", "files/execution-admission/" + name], timeout=10)
    assert record == b"\x00\x00\x00\x01\x00", "Admission still retained"
(output / "ordinary-terminal-result.json").write_text(json.dumps({
    "mainPid": main_pid, "newMainPid": new_main_pid, "runtimePid": runtime_pid,
    "shellPid": shell_pid, "bootId": boot, "newBootId": new_boot,
    "identityHashes": original, "starts": 1, "mainDeathReconnect": True,
    "runtimeDeathUnknown": True, "rebootExplicitSettlement": True,
}, indent=2))
