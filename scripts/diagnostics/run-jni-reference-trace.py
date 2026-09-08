#!/usr/bin/env python3
"""Bounded test-APK-only JNI observation; raw logs are evidence, not a soak pass."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("serial")
parser.add_argument("--count", type=int, default=400)
parser.add_argument("--navigate", action="store_true")
args = parser.parse_args()
if not args.serial.startswith("emulator-") or not 1 <= args.count <= 2000:
    parser.error("requires a dedicated emulator and count in 1..2000")
root = Path(__file__).resolve().parents[2]
adb = str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb")
package = "com.helix.feature.browser.test"
agent = f"/data/data/{package}/code_cache/helix-jni-trace.so"
output = root / "build/reference-trace" / f"{args.serial}-{'navigate' if args.navigate else 'bare'}-{args.count}"
output.mkdir(parents=True, exist_ok=True)

def run(*parts):
    return subprocess.check_output([adb, "-s", args.serial, *parts], text=True, timeout=30)

run("shell", "am", "force-stop", package)
run("install", "-r", str(root / "feature/browser/build/outputs/apk/androidTest/debug/browser-debug-androidTest.apk"))
run("push", str(root / "build/reference-trace/libjni-reference-trace.so"), "/data/local/tmp/helix-jni-trace.so")
run("shell", "run-as", package, "cp", "/data/local/tmp/helix-jni-trace.so", "code_cache/helix-jni-trace.so")
start = ["shell", "am", "start", "-W", "-n", f"{package}/com.helix.feature.browser.webview.RawWebViewControlActivity", "--ei", "iterations", str(args.count), "--el", "startDelayMs", "10000", "--el", "holdMs", "30000"]
if args.navigate:
    start += ["--ez", "navigateBeforeDestroy", "true"]
(output / "start.log").write_text(run(*start))
pid = run("shell", "pidof", package).strip()
if not pid.isdecimal():
    raise RuntimeError("expected exactly one test process")
run("shell", "am", "attach-agent", package, agent)

def logs():
    result = run("logcat", "-d", "--pid", pid, "-v", "threadtime")
    (output / "logcat.log").write_text(result)
    return result

text = ""
for _ in range(120):
    text = logs()
    if "FATAL EXCEPTION" in text or "Fatal signal" in text:
        raise RuntimeError("diagnostic process failed; see logcat")
    if f"PASS created={args.count}" in text:
        break
    time.sleep(1)
else:
    raise RuntimeError("bounded control timeout; do not count as a pass")
if "ATTACH result=0" not in text:
    raise RuntimeError("agent did not attach; control result is not trace evidence")
run("shell", "am", "attach-agent", package, agent)
for _ in range(10):
    text = logs()
    if "SUMMARY live=" in text and "CLASS new=" in text:
        break
    time.sleep(1)
else:
    raise RuntimeError("missing reference dump")
summary = [line for line in text.splitlines() if "HelixJniTrace" in line and re.search(r"SUMMARY|CLASS.*(LG8;|LWV/T6;)", line)]
(output / "summary.log").write_text("\n".join(summary) + "\n")
print(output.name, *summary, sep="\n", flush=True)
