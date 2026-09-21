#!/usr/bin/env python3
"""Exercise status/diff in the installed non-debuggable release APK on our owned emulator."""
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET

serial, destination = sys.argv[1:]
output = Path(destination)
owner = json.loads((output / "owner.json").read_text())
assert owner["serial"] == serial and not (output / "closed.json").exists()
os.kill(owner["pid"], 0)
package = os.environ["HELIX_GIT_PACKAGE"]
assert package in ("com.helix.agent", "com.helix.agent.developer")
adb = [str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb"), "-s", serial]


def device(*args):
    os.kill(owner["pid"], 0)
    assert not (output / "closed.json").exists()
    return subprocess.check_output(adb + list(args), timeout=90).decode().strip()


assert owner["avd"] in device("emu", "avd", "name").splitlines()
workspace = f"/data/user/0/{package}/files/workspaces/app/git-release-fixture"


def snapshot():
    device("shell", "uiautomator", "dump", "/sdcard/git-release-ui.xml")
    xml = device("shell", "cat", "/sdcard/git-release-ui.xml")
    (output / "release-ui.xml").write_text(xml)
    return ET.fromstring(xml)


steps = 0


def wait_for(action):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if action():
            return
        time.sleep(.2)
    raise TimeoutError("Release Git UI did not reach its expected state")


def click(*labels):
    global steps
    def attempt():
        for node in snapshot().iter("node"):
            if any(label in (node.get("text"), node.get("content-desc")) for label in labels) and node.get("enabled") == "true":
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
                device("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                return True
        return False
    wait_for(attempt)
    steps += 1


def contains(text):
    return any(text in node.get("text", "") for node in snapshot().iter("node"))


requests = []


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        requests.append(self.path)
        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        self.do_GET()

    def log_message(self, *args):
        pass


release = output / "release.apk"
shutil.copyfile(os.environ["HELIX_GIT_RELEASE_APK"], release)
config_before = device("shell", "su", "0", "cat", workspace + "/.git/config")
server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
thread = threading.Thread(target=server.serve_forever, daemon=True)
thread.start()
try:
    device("reverse", "tcp:43281", f"tcp:{server.server_port}")
    (output / "replace-release.txt").write_text(device("install", "-r", str(release)))
    package_dump = device("shell", "dumpsys", "package", package)
    (output / "release-package.txt").write_text(package_dump)
    flags = re.findall(r"(?:pkgFlags|flags)=\[([^]]*)\]", package_dump)
    assert flags and all("DEBUGGABLE" not in value.split() for value in flags), "Installed app is debuggable"
    installed = device("shell", "pm", "path", package).removeprefix("package:")
    device("pull", installed, str(output / "release-installed.apk"))
    digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    assert digest(release) == digest(output / "release-installed.apk")
    device("logcat", "-c")
    device("shell", "am", "start", "-S", "-W", "-n", package + "/com.helix.app.MainActivity")
    click("App navigation", "应用导航")
    click("Git")
    wait_for(lambda: contains("git-release-fixture"))
    click("a.txt")
    wait_for(lambda: contains("+changed"))
    (output / "release-diff.png").write_bytes(subprocess.check_output(adb + ["exec-out", "screencap", "-p"], timeout=15))
    config_after = device("shell", "su", "0", "cat", workspace + "/.git/config")
    assert config_after == config_before, "Read-only view changed repository config"
    marker = device("shell", "su", "0", "sh", "-c", f"'if test -e {workspace}/executed; then echo EXECUTED; else echo ABSENT; fi'")
    assert marker == "ABSENT", "Release viewer executed repository command"
    assert requests == [], "Release viewer accessed the configured HTTP remote"
    (output / "release-result.json").write_text(json.dumps({
        "status": "passed", "releaseApkSha256": digest(release), "debuggable": False,
        "user_steps_after_launch": steps, "diff_openable": True,
        "configured_command_marker": marker, "fixture_http_requests": len(requests),
        "config_unchanged": True, "boundary": "owned emulator; configured fixture remote only",
    }, indent=2) + "\n")
finally:
    (output / "release-logcat.txt").write_text(device("logcat", "-d", "-t", "5000"))
    device("reverse", "--remove", "tcp:43281")
    server.shutdown()
    server.server_close()
    thread.join(timeout=5)
