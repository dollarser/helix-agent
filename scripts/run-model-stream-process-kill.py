#!/usr/bin/env python3
"""Drive an actual app SIGKILL while a host HTTP model stream remains open."""
import argparse
import hashlib
import http.server
import json
import pathlib
import re
import shutil
import subprocess
import threading
import time

from model_kill_stream_fixtures import responses_stream, anthropic_stream
from android_process_control import kill_emulator_app, verify_emulator_signal_control


class ModelFixture(http.server.BaseHTTPRequestHandler):
    boundary = "body"
    requests = 0
    held = 0
    disconnected = threading.Event()
    lock = threading.Lock()

    def log_message(self, *_args):
        pass  # Never record request bodies, headers, or credentials.

    def do_GET(self):
        self.send_payload("application/json", json.dumps({"object": "list", "data": [
            {"id": "fixture-model-a", "object": "model"}]}))

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        with self.lock:
            type(self).requests += 1
        request = json.loads(body)
        if "GOAL_MODEL_KILL_HOLD" in body.decode():
            with self.lock:
                type(self).held += 1
            if self.boundary == "body":
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Connection", "close")
                self.end_headers()
                partial = (responses_stream(held=True) if self.path.endswith("/responses") else
                           anthropic_stream(held=True) if self.path.endswith("/messages") else self.chunk({"content": "partial"}))
                self.wfile.write(partial.encode())
                self.wfile.flush()
            self.connection.settimeout(40)
            if self.connection.recv(1) == b"":
                self.disconnected.set()
        else:
            tools = bool(request.get("tools"))
            delta = {"tool_calls": [{"id": "fixture-call", "index": 0, "type": "function",
                      "function": {"name": "echo", "arguments": '{"text":"probe"}'}}]} if tools else {"content": "ok"}
            stream = self.chunk(delta) + self.chunk({}, "tool_calls" if tools else "stop")
            stream += 'data: {"id":"fixture","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}\n\n'
            stream += "data: [DONE]\n\n"
            if self.path.endswith("/responses"):
                stream = responses_stream(tools=tools)
            elif self.path.endswith("/messages"):
                stream = anthropic_stream(tools=tools)
            self.send_payload("text/event-stream", stream)

    @staticmethod
    def chunk(delta, finish=None):
        return "data: " + json.dumps({"id": "fixture", "object": "chat.completion.chunk",
            "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}) + "\n\n"

    def send_payload(self, kind, text):
        data = text.encode()
        self.send_response(200)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def run_phase(base, port, phase, output, protocol, boundary):
    path = output / f"model-kill-{phase}.log"
    command = base + ["shell", "am", "instrument", "-w", "-r", "-e", "class",
        "com.helix.app.chat.ModelStreamProcessKillDeviceTest", "-e", "model.kill.port", str(port),
        "-e", "model.kill.phase", phase, "-e", "model.kill.protocol", protocol, "-e", "model.kill.boundary", boundary,
        "com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner"]
    with path.open("w") as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
        if phase == "prepare":
            deadline = time.monotonic() + 25
            match = None
            while time.monotonic() < deadline:
                match = re.search(r"MODEL_STREAM_KILL_READY pid=(\d+)", path.read_text())
                if (match and ModelFixture.held == 1) or process.poll() is not None:
                    break
                time.sleep(.05)
            if not match:
                raise RuntimeError(f"No ready marker; inspect process and {path}; do not reset fixture")
            pid = match.group(1)
            assert pid in subprocess.check_output(base + ["shell", "pidof", "com.helix.agent"], text=True).split()
            assert ModelFixture.held == 1
            kill_emulator_app(base, "com.helix.agent", pid)
            process.wait(timeout=15)
            assert "shortMsg=Process crashed." in path.read_text()
            assert ModelFixture.disconnected.wait(5), "Host did not observe socket EOF"
            result = dict(phase=phase, pid=int(pid), signal="SIGKILL", signalAuthority="emulator host su 0",
                          socketEOF=True, log=path.name)
        else:
            process.wait(timeout=30)
            assert "OK (1 test)" in path.read_text(), path.read_text()
            observed = re.search(r"MODEL_STREAM_RECOVERED tokens=(\d+) millis=(\d+) calls=(\d+)", path.read_text())
            assert observed
            tokens, millis, calls = map(int, observed.groups())
            result = dict(phase=phase, tests=1, chargedTokens=tokens, chargedMillis=millis, modelCalls=calls, log=path.name)
    print(json.dumps(result), flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--boundary", choices=["headers", "body"], default="body")
    parser.add_argument("--protocol", default="OPENAI_CHAT_COMPLETIONS",
                        choices=["OPENAI_CHAT_COMPLETIONS", "OPENAI_RESPONSES", "ANTHROPIC_MESSAGES"])
    parser.add_argument("--adb", default=shutil.which("adb"))
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    if not args.adb or not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Pass adb and a dedicated emulator serial")
    args.output.mkdir(parents=True, exist_ok=False)
    base = [args.adb, "-s", args.serial]
    verify_emulator_signal_control(base)
    ModelFixture.boundary = args.boundary
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), ModelFixture)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        records = [run_phase(base, server.server_port, "prepare", args.output, args.protocol, args.boundary)]
        requests = ModelFixture.requests
        for phase in ["recover", "recover-final"]:
            records.append(run_phase(base, server.server_port, phase, args.output, args.protocol, args.boundary))
            assert ModelFixture.requests == requests, "Recovery replayed a model request"
        root = pathlib.Path(__file__).resolve().parents[1]
        apks = ["app/build/outputs/apk/consumer/debug/app-consumer-debug.apk",
                "app/build/outputs/apk/androidTest/consumer/debug/app-consumer-debug-androidTest.apk"]
        result = dict(serial=args.serial, protocol=args.protocol, boundary=args.boundary, api=subprocess.check_output(
            base + ["shell", "getprop", "ro.build.version.sdk"], text=True).strip(),
            records=records, heldRequests=ModelFixture.held,
            postRequestsBeforeRecovery=requests, postRequestsAfterRecovery=ModelFixture.requests,
            apks={p: hashlib.sha256((root / p).read_bytes()).hexdigest() for p in apks},
            scope="Production ChatService/HTTP/Room and application startup recovery; host scripted model, one SIGKILL")
        (args.output / "model-kill-result.json").write_text(json.dumps(result, indent=2) + "\n")
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


if __name__ == "__main__":
    main()
