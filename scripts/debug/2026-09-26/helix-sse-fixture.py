#!/usr/bin/env python3
"""Host-side OpenAI-compatible SSE fixture for HXA-226 device verification.

Paired with `adb reverse tcp:18443 tcp:18443` this lets the on-device app talk to a
model endpoint that the host fully controls, so the RUNNING / CANCELLING / CANCELLED
composer states are reachable and screenshot-able without a real provider.

Endpoints
  GET  /v1/models              -> two ids, one deliberately long (layout verification)
  POST /v1/chat/completions    -> SSE; emits one chunk every --interval seconds forever

The endless stream is the point: it keeps the Turn in RUNNING until the user taps Stop,
which makes the transient CANCELLING state observable instead of instantaneous.

Usage: helix-sse-fixture.py [--port 18443] [--interval 2.0]
"""
import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODEL_IDS = [
    "fixture-model-a",
    "a-very-long-model-name-used-to-verify-composer-layout-32b-instruct",
]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # keep the console readable
        print(f"[sse] {fmt % args}", flush=True)

    def _json(self, payload, status=200):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.rstrip("/").endswith("/v1/models"):
            self._json(
                {
                    "object": "list",
                    "data": [{"id": m, "object": "model", "owned_by": "fixture"} for m in MODEL_IDS],
                }
            )
        else:
            self._json({"error": {"message": "not found"}}, 404)

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        if not self.path.rstrip("/").endswith("/v1/chat/completions"):
            self._json({"error": {"message": "not found"}}, 404)
            return

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.end_headers()

        counter = 0
        try:
            while True:
                counter += 1
                chunk = {
                    "id": "chatcmpl-fixture",
                    "object": "chat.completion.chunk",
                    "model": MODEL_IDS[0],
                    "choices": [
                        {
                            "index": 0,
                            "delta": {"content": f"token{counter} "},
                            "finish_reason": None,
                        }
                    ],
                }
                self.wfile.write(f"data: {json.dumps(chunk)}\n\n".encode())
                self.wfile.flush()
                time.sleep(self.interval)
        except (BrokenPipeError, ConnectionResetError):
            # The app closed the socket — i.e. Stop actually cancelled the stream.
            print(f"[sse] client disconnected after {counter} chunks (cancel observed)", flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=18443)
    ap.add_argument("--interval", type=float, default=2.0)
    args = ap.parse_args()
    Handler.interval = args.interval
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"[sse] listening on 127.0.0.1:{args.port} interval={args.interval}s", flush=True)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
