#!/usr/bin/env python3
"""External OpenAI-compatible fixture for normal-process session-input SIGKILL tests."""

import argparse
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import socket
import threading
import time


class FixtureState:
    def __init__(self, events: Path):
        self.events = events
        self.lock = threading.Lock()
        self.mode = "complete"
        self.chat_count = 0
        self.held_count = 0
        self.disconnected_count = 0
        self.requests = []

    def event(self, kind, **values):
        row = {"kind": kind, "atMonotonic": time.monotonic(), **values}
        with self.events.open("a") as output:
            output.write(json.dumps(row, sort_keys=True) + "\n")

    def reset(self):
        with self.lock:
            self.chat_count = 0
            self.held_count = 0
            self.disconnected_count = 0
            self.requests = []
            self.mode = "complete"
        self.event("reset")

    def set_mode(self, mode):
        if mode not in {"complete", "hold"}:
            raise ValueError("unsupported mode")
        with self.lock:
            self.mode = mode
        self.event("mode", mode=mode)

    def chat(self, body):
        digest = hashlib.sha256(body).hexdigest()
        text = body.decode("utf-8", errors="replace")
        with self.lock:
            self.chat_count += 1
            ordinal = self.chat_count
            mode = self.mode
            self.requests.append(
                {
                    "ordinal": ordinal,
                    "sha256": digest,
                    "bytes": len(body),
                    "hasActiveInput": "ACTIVE-PROCESS-INPUT" in text,
                    "hasQueuedInput": "QUEUED-PROCESS-INPUT" in text,
                }
            )
        self.event("chat", ordinal=ordinal, sha256=digest, bytes=len(body), mode=mode)
        return ordinal, mode

    def held(self, ordinal):
        with self.lock:
            self.held_count += 1
        self.event("held", ordinal=ordinal)

    def disconnected(self, ordinal):
        with self.lock:
            self.disconnected_count += 1
        self.event("disconnected", ordinal=ordinal)

    def snapshot(self):
        with self.lock:
            return {
                "mode": self.mode,
                "chatCount": self.chat_count,
                "heldCount": self.held_count,
                "disconnectedCount": self.disconnected_count,
                "requests": list(self.requests),
            }


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    @property
    def fixture(self):
        return self.server.fixture

    def log_message(self, *_args):
        return

    def read_body(self):
        length = int(self.headers.get("Content-Length", "0"))
        return self.rfile.read(length)

    def json_response(self, status, value):
        body = json.dumps(value, sort_keys=True).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)
        self.wfile.flush()

    def do_GET(self):
        if self.path == "/__control/state":
            self.json_response(200, self.fixture.snapshot())
        elif self.path == "/v1/models":
            self.json_response(
                200,
                {"object": "list", "data": [{"id": "fixture-model-a", "object": "model"}]},
            )
        else:
            self.json_response(404, {"error": "not found"})

    def do_POST(self):
        body = self.read_body()
        if self.path == "/__control/reset":
            self.fixture.reset()
            self.json_response(200, self.fixture.snapshot())
            return
        if self.path == "/__control/mode":
            self.fixture.set_mode(json.loads(body).get("mode"))
            self.json_response(200, self.fixture.snapshot())
            return
        if self.path != "/v1/chat/completions":
            self.json_response(404, {"error": "not found"})
            return
        ordinal, mode = self.fixture.chat(body)
        if mode == "complete":
            payload = (
                'data: {"choices":[{"index":0,"delta":{"content":"fixture answer"},'
                '"finish_reason":null}]}\n\n'
                'data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}\n\n'
                "data: [DONE]\n\n"
            ).encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(payload)
            self.wfile.flush()
            return

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(
            b'data: {"choices":[{"index":0,"delta":{"content":"partial"},"finish_reason":null}]}\n\n'
        )
        self.wfile.flush()
        self.fixture.held(ordinal)
        self.connection.settimeout(0.25)
        deadline = time.monotonic() + 300
        while time.monotonic() < deadline:
            try:
                if self.connection.recv(1, socket.MSG_PEEK) == b"":
                    self.fixture.disconnected(ordinal)
                    return
            except TimeoutError:
                continue
            except OSError:
                self.fixture.disconnected(ordinal)
                return
        raise TimeoutError("held model response was not disconnected")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--ready-file", type=Path, required=True)
    parser.add_argument("--events", type=Path, required=True)
    args = parser.parse_args()
    args.ready_file.parent.mkdir(parents=True, exist_ok=True)
    args.events.parent.mkdir(parents=True, exist_ok=True)
    args.events.write_text("")
    state = FixtureState(args.events)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.fixture = state
    args.ready_file.write_text(json.dumps({"port": server.server_port, "pid": __import__("os").getpid()}))
    state.event("ready", port=server.server_port)
    try:
        server.serve_forever(poll_interval=0.1)
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
