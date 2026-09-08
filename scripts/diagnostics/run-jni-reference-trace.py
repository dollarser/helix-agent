#!/usr/bin/env python3
"""Bounded test-APK-only JNI observation; raw logs are evidence, not a soak pass."""
import argparse
import hashlib
import json
import http.server
import threading
import os
from pathlib import Path
import re
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("serial")
parser.add_argument("--count", type=int, default=400)
parser.add_argument("--navigate", action="store_true")
parser.add_argument("--scenario", choices=["empty", "denied", "early-close", "stop-close", "settled-close", "clear-history", "network-close", "network-stop", "network-background", "network-recreate"])
args = parser.parse_args()
if not args.serial.startswith("emulator-") or not 1 <= args.count <= 2000:
    parser.error("requires a dedicated emulator and count in 1..2000")
if args.scenario and args.navigate:
    parser.error("--scenario and --navigate are independent controls")
root = Path(__file__).resolve().parents[2]
adb = str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb")
package = "com.helix.feature.browser.test"
agent = f"/data/data/{package}/code_cache/helix-jni-trace.so"
output = root / "build/reference-trace" / f"{args.serial}-{args.scenario or ('navigate' if args.navigate else 'bare')}-{args.count}"
output.mkdir(parents=True, exist_ok=True)

def run(*parts):
    return subprocess.check_output([adb, "-s", args.serial, *parts], text=True, timeout=30)

run("shell", "am", "force-stop", package)
run("install", "-r", str(root / "feature/browser/build/outputs/apk/androidTest/debug/browser-debug-androidTest.apk"))
run("push", str(root / "build/reference-trace/libjni-reference-trace.so"), "/data/local/tmp/helix-jni-trace.so")
run("shell", "run-as", package, "cp", "/data/local/tmp/helix-jni-trace.so", "code_cache/helix-jni-trace.so")
start = ["shell", "am", "start", "-W", "-n", f"{package}/com.helix.feature.browser.webview.RawWebViewControlActivity", "--ei", "iterations", str(args.count), "--el", "startDelayMs", "10000", "--el", "holdMs", "30000"]
if args.scenario:
    start[5] = f"{package}/com.helix.feature.browser.webview.ControllerReferenceControlActivity"
    start += ["--es", "scenario", args.scenario]
requests_seen = []
if args.scenario and args.scenario.startswith("network-"):
    class SlowResponse(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            requests_seen.append(self.path)
            time.sleep(3)
            try:
                self.send_response(200)
                self.send_header("Content-Type", "text/html")
                self.end_headers()
                self.wfile.write(b"<title>network control</title>")
            except (BrokenPipeError, ConnectionResetError):
                pass  # Expected cancelled socket; count records request arrival, not completion.
        def log_message(self, *args):
            pass
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), SlowResponse)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    start += ["--es", "networkUrl", f"http://10.0.2.2:{server.server_port}/slow"]
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
resumed = set()
acknowledged = set()
for _ in range(4000 if args.scenario and args.scenario.startswith("network-") else 120):
    text = logs()
    if "FATAL EXCEPTION" in text or "Fatal signal" in text or "OVERFLOW observation invalid" in text:
        raise RuntimeError("diagnostic process failed; see logcat")
    if f"PASS created={args.count}" in text:
        break
    if args.scenario and args.scenario.startswith("network-"):
        for request in list(requests_seen):
            iteration = request.split("iteration=")[-1]
            if iteration.isdecimal() and iteration not in acknowledged:
                acknowledged.add(iteration)
                run("shell", "am", "broadcast", "-a", "com.helix.feature.browser.test.REQUEST_RECEIVED", "-p", package, "--ei", "iteration", iteration)
    if args.scenario == "network-background":
        markers = re.findall(r"BACKGROUND iteration=(\d+)", text)
        if markers and markers[-1] not in resumed:
            resumed.add(markers[-1])
            run("shell", "am", "start", "-W", "-f", "0x00020000", "-n", start[5])
        time.sleep(0.05)
    else:
        time.sleep(0.05 if args.scenario and args.scenario.startswith("network-") else 1)
else:
    raise RuntimeError("bounded control timeout; do not count as a pass")
if "ATTACH result=0" not in text:
    raise RuntimeError("agent did not attach; control result is not trace evidence")
if "BEGIN reference control" not in text or text.index("ATTACH result=0") > text.index("BEGIN reference control"):
    raise RuntimeError("control started before observer attachment; evidence invalid")
run("shell", "am", "attach-agent", package, agent)
for _ in range(10):
    text = logs()
    if "END reference dump" in text:
        break
    time.sleep(1)
else:
    raise RuntimeError("missing reference dump")
summary = [line for line in text.splitlines() if "HelixJniTrace" in line and re.search(r"SUMMARY|CLASS.*(LG8;|LWV/T6;)", line)]
(output / "summary.log").write_text("\n".join(summary) + "\n")
if args.scenario and args.scenario.startswith("network-"):
    (output / "server-requests.json").write_text(json.dumps(requests_seen, indent=2))
    if len(set(requests_seen)) < args.count:
        raise RuntimeError("not every iteration reached the HTTP server")
metadata = {
    "serial": args.serial, "pid": pid, "count": args.count,
    "scenario": args.scenario or ("navigate" if args.navigate else "bare"),
    "fingerprint": run("shell", "getprop", "ro.build.fingerprint").strip(),
    "webview": run("shell", "dumpsys", "webviewupdate"),
    "agent_sha256": hashlib.sha256((root / "build/reference-trace/libjni-reference-trace.so").read_bytes()).hexdigest(),
    "apk_sha256": hashlib.sha256((root / "feature/browser/build/outputs/apk/androidTest/debug/browser-debug-androidTest.apk").read_bytes()).hexdigest(),
}
(output / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
print(output.name, *summary, sep="\n", flush=True)
