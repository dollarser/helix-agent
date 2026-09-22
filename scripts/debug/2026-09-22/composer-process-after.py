#!/usr/bin/env python3
"""Exercise normal composer recovery and a seeded durable cancellation boundary without replay."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts"))
from android_process_control import kill_emulator_app

serial, destination = sys.argv[1:]
output = Path(destination)
owner = json.loads((output / "owner.json").read_text())
assert owner["serial"] == serial
os.kill(owner["pid"], 0)
package = os.environ["HXA214_PACKAGE"]
assert package in ("com.helix.agent", "com.helix.agent.developer")
base = [str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb"), "-s", serial]
runner = package + ".test/com.helix.app.HelixAndroidJUnitRunner"


def adb(*args):
    return subprocess.check_output(base + list(args), text=True, timeout=40)


def nodes(label):
    adb("shell", "uiautomator", "dump", "/sdcard/helix-composer.xml")
    raw = adb("shell", "cat", "/sdcard/helix-composer.xml")
    (output / (label + ".xml")).write_text(raw)
    return list(ET.fromstring(raw).iter("node"))


def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def wait_text(text, label):
    choices = (text,) if isinstance(text, str) else text
    for _ in range(20):
        found = next(
            (node for node in nodes(label) if node.get("text") in choices or node.get("content-desc") in choices),
            None,
        )
        if found is not None:
            return found
        time.sleep(0.5)
    raise RuntimeError("UI text not found: " + repr(choices))


def open_session(title):
    adb("shell", "am", "start", "-n", package + "/com.helix.app.MainActivity")
    time.sleep(1)
    tap(wait_text(title, "session-list"))


def replace_editor_text(replacement):
    for _ in range(3):
        fields = [node for node in nodes("composer-focus") if node.get("class") == "android.widget.EditText"]
        assert len(fields) == 1
        if fields[0].get("focused") != "true":
            tap(fields[0])
        adb("shell", "input", "keyevent", "123")
        time.sleep(0.3)
        adb("shell", "input", "keyevent", *(["67"] * (len(fields[0].get("text", "")) + 1)))
        cleared = [
            node for node in nodes("composer-cleared") if node.get("class") == "android.widget.EditText"
        ]
        if len(cleared) == 1 and cleared[0].get("text") == "":
            break
    else:
        raise RuntimeError("Could not clear the focused composer before replacement")
    adb("shell", "input", "text", replacement)
    wait_text(replacement, "composer-written")


cancel_seed = json.loads(adb("shell", "run-as", package, "cat", "files/composer-cancellation-seed.json"))
assert cancel_seed["state"] == "CANCELLING" and cancel_seed["seededBoundary"] is True
(output / "cancellation-seed.json").write_text(json.dumps(cancel_seed, indent=2))

# The cancellation boundary was seeded before normal application startup. Startup
# recovery parks it; the following real MainActivity SIGKILL/reopen must not replay it.
# This does not claim a probabilistic kill between a live Stop click and settlement.
open_session("COMPOSER-RECOVERY")
wait_text("RECOVER-DRAFT-214", "composer-before")
replace_editor_text("NORMAL-PROCESS-DRAFT-214")
# The ordinary composer persists after a bounded 500 ms debounce. The post-kill
# UI and instrumentation assertions below are the durable receipt for that save.
time.sleep(2)
pid = int(adb("shell", "pidof", package).strip())
kill_emulator_app(base, package, pid)
open_session("COMPOSER-RECOVERY")
wait_text("NORMAL-PROCESS-DRAFT-214", "composer-restored")
new_pid = int(adb("shell", "pidof", package).strip())
assert new_pid != pid

tap(wait_text(("会话列表", "Session list"), "after-back"))
tap(wait_text("COMPOSER-ACCEPTED", "accepted-session-list"))
for _ in range(20):
    accepted_nodes = nodes("accepted-visible")
    fields = [node for node in accepted_nodes if node.get("class") == "android.widget.EditText"]
    if (
        any(node.get("text") == "ACCEPTED-COMPOSER-214" for node in accepted_nodes)
        and len(fields) == 1
        and fields[0].get("text") == ""
    ):
        break
    time.sleep(0.5)
else:
    raise RuntimeError("Accepted composer receipt was not cleared without replay")
time.sleep(2)
accepted_nodes = nodes("accepted-stable")
fields = [node for node in accepted_nodes if node.get("class") == "android.widget.EditText"]
assert any(node.get("text") == "ACCEPTED-COMPOSER-214" for node in accepted_nodes)
assert len(fields) == 1 and fields[0].get("text") == ""

tap(wait_text(("会话列表", "Session list"), "accepted-back"))
tap(wait_text("COMPOSER-CANCELLING", "cancellation-session-list"))
wait_text("Seeded cancellation boundary", "cancellation-visible")
time.sleep(2)
adb("logcat", "-c")
result = adb(
    "shell",
    "am",
    "instrument",
    "-w",
    "-e",
    "class",
    "com.helix.app.chat.ComposerProcessRecoveryDeviceTest#verifyComposerRecovery",
    runner,
)
(output / "verify-instrumentation.txt").write_text(result)
assert "OK (1 test)" in result and "FAILURES!!!" not in result, result
(output / "verify-logcat.txt").write_text(adb("logcat", "-d", "-s", "TestRunner"))
cancel_verified = json.loads(adb("shell", "run-as", package, "cat", "files/composer-cancellation-verified.json"))
assert cancel_verified["turnId"] == cancel_seed["turnId"]
assert cancel_verified["state"] == "INTERRUPTED"
assert cancel_verified["uncertainCall"] == cancel_seed["runningCall"]
assert cancel_verified["newExecutions"] == 0 and cancel_verified["newModelCalls"] == 0
assert cancel_verified["repeatedRecoveryUnchanged"] is True
(output / "cancellation-verified.json").write_text(json.dumps(cancel_verified, indent=2))
(output / "normal-process.json").write_text(
    json.dumps(
        {
            "beforePid": pid,
            "afterPid": new_pid,
            "normalActivity": True,
            "draftRestored": True,
            "acceptedHistoryVisible": True,
            "acceptedComposerCleared": True,
            "cancellationBoundarySeeded": True,
            "cancellationRecovery": cancel_verified,
        },
        indent=2,
    )
)
print("Normal composer recovery, accepted receipt and seeded cancellation without replay verified", flush=True)
