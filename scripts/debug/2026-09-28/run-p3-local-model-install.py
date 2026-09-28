#!/usr/bin/env python3
"""Run the P3 Android HTTP install/probe fixture against a pinned local GGUF; never downloads it."""
import argparse
import hashlib
import http.server
import json
from pathlib import Path
import socketserver
import subprocess
import threading


class RangeHandler(http.server.BaseHTTPRequestHandler):
    model = None
    requests = []

    def do_GET(self):
        if self.path != '/model.gguf':
            self.send_error(404)
            return
        size = self.model.stat().st_size
        raw = self.headers.get('Range')
        start = 0
        status = 200
        if raw:
            prefix = 'bytes='
            if not raw.startswith(prefix) or not raw.endswith('-'):
                self.send_error(416)
                return
            start = int(raw[len(prefix):-1])
            if not 0 < start < size:
                self.send_error(416)
                return
            status = 206
        self.requests.append({'range': raw, 'start': start, 'status': status})
        self.send_response(status)
        self.send_header('Content-Type', 'application/octet-stream')
        self.send_header('Accept-Ranges', 'bytes')
        self.send_header('Content-Length', str(size - start))
        if status == 206:
            self.send_header('Content-Range', f'bytes {start}-{size - 1}/{size}')
        self.send_header('Connection', 'close')
        self.end_headers()
        with self.model.open('rb') as source:
            source.seek(start)
            try:
                while True:
                    chunk = source.read(1024 * 1024)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
            except (BrokenPipeError, ConnectionResetError):
                pass

    def log_message(self, fmt, *args):
        return


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model', default='build/hxa222-real-model/Qwen3-0.6B-Q4_K_M.gguf')
    parser.add_argument('--sha', default='ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a')
    parser.add_argument('--size', type=int, default=396705472)
    parser.add_argument('--avd', default='Helix_HXA210_API36')
    parser.add_argument('--emulator-port', type=int, default=5676)
    parser.add_argument('--output', default='build/p3-local-model-install/api36-developer')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[3]
    model = (root / args.model).resolve()
    assert model.is_file(), model
    assert model.stat().st_size == args.size
    with model.open('rb') as source:
        assert hashlib.file_digest(source, 'sha256').hexdigest() == args.sha
    RangeHandler.model = model
    RangeHandler.requests = []
    with socketserver.ThreadingTCPServer(('127.0.0.1', 0), RangeHandler) as server:
        server.daemon_threads = True
        port = server.server_address[1]
        thread = threading.Thread(target=server.serve_forever, name='p3-model-http', daemon=True)
        thread.start()
        command = [
            'python3', 'scripts/run-owned-emulator.py',
            '--avd', args.avd,
            '--port', str(args.emulator_port),
            '--memory-mb', '4096',
            '--cores', '4',
            '--density-dpi', '400',
            '--apk', 'app/build/outputs/apk/developer/debug/app-developer-debug.apk',
            '--test-apk', 'app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk',
            '--classes', 'com.helix.app.localmodel.LocalModelInstallDeviceTest',
            '--runner', 'com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner',
            '--output', args.output,
            '--timeout', '900',
            '--reverse-port', str(port),
            '--instrument-arg', f'p3ModelPort={port}',
            '--instrument-arg', f'p3ModelSha={args.sha}',
            '--instrument-arg', f'p3ModelSize={args.size}',
            '--raw-results',
        ]
        try:
            subprocess.run(command, cwd=root, check=True, timeout=1200)
        finally:
            server.shutdown()
            thread.join(timeout=10)
    output = root / args.output
    (output / 'http-fixture.json').write_text(json.dumps(RangeHandler.requests, indent=2) + '\n')
    assert len(RangeHandler.requests) >= 2, RangeHandler.requests
    assert RangeHandler.requests[0]['range'] is None, RangeHandler.requests
    assert any(row['status'] == 206 and row['start'] > 0 for row in RangeHandler.requests[1:]), RangeHandler.requests
    print(json.dumps({'requests': RangeHandler.requests, 'modelSha': args.sha, 'modelBytes': args.size}, indent=2))


if __name__ == '__main__':
    main()
