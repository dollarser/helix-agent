#!/usr/bin/env python3
"""Causal GC intervention on a dedicated emulator, never a stability acceptance run."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

root = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(root / "scripts"))
from android_process_control import verify_emulator_signal_control

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("serial")
args = parser.parse_args()
base = [str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb"), "-s", args.serial]
verify_emulator_signal_control(base)
package = "com.helix.feature.browser.test"
output = root / "build/reference-trace" / f"{args.serial}-binder-release"
output.mkdir(parents=True, exist_ok=True)

def run(*parts):
    return subprocess.check_output(base + list(parts), text=True, timeout=30)

run("shell", "am", "force-stop", package)
run("shell", "am", "start", "-W", "-n", f"{package}/com.helix.feature.browser.webview.RawWebViewControlActivity", "--ei", "iterations", "1000", "--ez", "autofillOnly", "true", "--el", "holdMs", "30000")
app_pid = run("shell", "pidof", package).strip()
server_pid = run("shell", "pidof", "system_server").strip()
if not app_pid.isdecimal() or not server_pid.isdecimal():
    raise RuntimeError("expected exactly one PID per target")
for _ in range(30):
    text = run("logcat", "-d", "--pid", app_pid, "-v", "threadtime")
    if "PASS created=1000" in text:
        break
    time.sleep(1)
else:
    raise RuntimeError("Autofill control did not finish")

results = {}
def snapshot(label):
    if run("shell", "pidof", package).strip() != app_pid or run("shell", "pidof", "system_server").strip() != server_pid:
        raise RuntimeError("a target PID changed; intervention is invalid")
    info = run("shell", "dumpsys", "meminfo", app_pid)
    proxies = run("shell", "dumpsys", "activity", "binder-proxies")
    (output / f"{label}-app-meminfo.txt").write_text(info)
    (output / f"{label}-server-proxies.txt").write_text(proxies)
    results[label] = {"app_local_binders": int(re.search(r"Local Binders:\s*(\d+)", info).group(1))}
    print(label, results[label], flush=True)

def signal_gc(name, expected):
    if run("shell", "pidof", name).strip() != expected:
        raise RuntimeError("target changed before diagnostic signal")
    run("shell", "su", "0", "kill", "-10", expected)

snapshot("before")
time.sleep(5)
snapshot("idle")
signal_gc(package, app_pid)
time.sleep(2)
snapshot("after-app-gc")
signal_gc("system_server", server_pid)
time.sleep(2)
snapshot("after-server-gc")
(output / "logcat.log").write_text(run("logcat", "-d", "-v", "threadtime"))
(output / "result.json").write_text(json.dumps({"status": "DIAGNOSTIC_INTERVENTION_NOT_ACCEPTANCE", "app_pid": app_pid, "server_pid": server_pid, "samples": results}, indent=2) + "\n")
