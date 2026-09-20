#!/usr/bin/env python3
"""Drive one synthetic Job through normal UI; kill only main PID outside instrumentation."""
import http.server
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from android_process_control import kill_emulator_app

serial, destination = sys.argv[1:]
output = Path(destination)
adb = str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb")
package = "com.helix.agent.developer"


def device(*args):
    return subprocess.check_output([adb, "-s", serial, *args], text=True, timeout=30).strip()


facts = dict(line.split("=", 1) for line in device(
    "shell", "run-as", package, "cat", "no_backup/host-job-journey.properties"
).splitlines() if "=" in line and not line.startswith("#"))
if not re.fullmatch(r"goal-[0-9a-f-]+\.txt", facts["output"]):
    raise RuntimeError("Invalid fixture output name")


class Model(http.server.BaseHTTPRequestHandler):
    calls = 0

    def log_message(self, *_args):
        pass

    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        Model.calls += 1
        complete = any(message.get("role") == "tool" for message in request.get("messages", []))
        delta = {"content": "Background command accepted."} if complete else {"tool_calls": [{
            "index": 0, "id": "host-job-call", "type": "function", "function": {
                "name": "code.linux.job.start", "arguments": json.dumps({
                    "script": "sleep 12; printf host-job-result >> result.txt",
                    "output": "scope:app:output/" + facts["output"], "leaseSeconds": 20,
                })}}]}
        chunks = [{"choices": [{"index": 0, "delta": delta, "finish_reason": None}]},
                  {"choices": [{"index": 0, "delta": {}, "finish_reason": "stop" if complete else "tool_calls"}],
                   "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}}]
        body = ("".join("data: " + json.dumps(chunk) + "\n\n" for chunk in chunks) + "data: [DONE]\n\n").encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def snapshot():
    device("shell", "uiautomator", "dump", "/sdcard/host-job-ui.xml")
    xml = device("shell", "cat", "/sdcard/host-job-ui.xml")
    (output / "host-job-ui.xml").write_text(xml)
    return ET.fromstring(xml)


def click(predicate):
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        for node in snapshot().iter("node"):
            if predicate(node.attrib):
                bounds = list(map(int, re.findall(r"\d+", node.attrib["bounds"])))
                device("shell", "input", "tap", str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
                return
        time.sleep(.3)
    raise RuntimeError("UI target not found; inspect host-job-ui.xml")


server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Model)
threading.Thread(target=server.serve_forever, daemon=True).start()
try:
    device("reverse", "tcp:" + facts["port"], "tcp:" + str(server.server_port))
    device("shell", "am", "start", "-W", "-n", package + "/com.helix.app.MainActivity")
    click(lambda a: a.get("text") == "Detached Goal fixture")
    click(lambda a: a.get("text") == "Chat ▾")
    click(lambda a: a.get("text") == "Act")
    click(lambda a: a.get("class") == "android.widget.EditText")
    device("shell", "input", "text", "Run%sthe%sbackground%scommand")
    click(lambda a: a.get("content-desc") in ("Send", "发送"))
    deadline = time.monotonic() + 20
    record = None
    while time.monotonic() < deadline:
        roots = device("shell", "run-as", package, "sh", "-c",
                       "'if [ -d files/runtime/jobs ]; then ls files/runtime/jobs; fi'").splitlines()
        for job in roots:
            if re.fullmatch(r"job_[0-9a-f]{12}", job):
                candidate = json.loads(device("shell", "run-as", package, "cat", f"files/runtime/jobs/{job}/record.json"))
                if candidate["state"] == "RUNNING":
                    record = candidate
                    break
        if record is not None and Model.calls >= 2:
            break
        time.sleep(.2)
    if record is None or Model.calls != 2:
        snapshot()
        raise RuntimeError("No single accepted background Job through UI")
    job = record["jobId"]
    main_pid = device("shell", "pidof", package)
    runtime_pid = device("shell", "pidof", package + ":proot")
    boot = device("shell", "cat", "/proc/sys/kernel/random/boot_id")
    kill_emulator_app([adb, "-s", serial], package, main_pid)
    deadline = time.monotonic() + 15
    launches = []
    while time.monotonic() < deadline:
        launches.append(device("shell", "am", "start", "-W", "-n", package + "/com.helix.app.MainActivity"))
        process = subprocess.run([adb, "-s", serial, "shell", "pidof", package], capture_output=True, text=True, timeout=10)
        new_pid = process.stdout.strip()
        if process.returncode == 0 and new_pid.isdigit() and new_pid != main_pid:
            break
        time.sleep(.2)
    else:
        (output / "host-job-launches.txt").write_text("\n".join(launches))
        (output / "host-job-process-logcat.txt").write_text(device("logcat", "-d", "-s", "ActivityManager", "AndroidRuntime"))
        raise RuntimeError("Main PID did not change after ordinary relaunch")
    states = []
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if device("shell", "pidof", package + ":proot") != runtime_pid:
            raise RuntimeError("Runtime PID changed")
        record = json.loads(device("shell", "run-as", package, "cat", f"files/runtime/jobs/{job}/record.json"))
        states.append(record["state"])
        if record["state"] == "SUCCEEDED":
            break
        if record["state"] != "RUNNING":
            raise RuntimeError("Unexpected Job state: " + record["state"])
        time.sleep(.2)
    if "RUNNING" not in states or states[-1] != "SUCCEEDED" or Model.calls != 2:
        raise RuntimeError("Missing original running-to-success evidence or model replay")
    if device("shell", "cat", "/proc/sys/kernel/random/boot_id") != boot:
        raise RuntimeError("Device rebooted")
    if device("shell", "pidof", package) != new_pid:
        raise RuntimeError("Recovered main process did not remain alive")
    result = device("shell", "am", "instrument", "-w", "-e", "class",
                    "com.helix.app.proot.DetachedJobHostJourneyDeviceTest", "-e", "hostJobPhase", "verify",
                    package + ".test/com.helix.app.HelixAndroidJUnitRunner")
    (output / "host-job-verify.txt").write_text(result)
    if "OK (1 test)" not in result or "FAILURES" in result:
        raise RuntimeError("Final collection verification failed")
    (output / "host-job-result.json").write_text(json.dumps({
        "mainPid": main_pid, "newMainPid": new_pid, "runtimePid": runtime_pid, "bootId": boot,
        "jobId": job, "states": states, "modelCalls": Model.calls, "verifyTests": 1,
    }, indent=2))
finally:
    server.shutdown()
    server.server_close()
    device("reverse", "--remove", "tcp:" + facts["port"])
