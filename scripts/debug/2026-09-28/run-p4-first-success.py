#!/usr/bin/env python3
"""P4 clean-state local-model -> workspace -> tool/artifact -> process-reopen journey."""
import argparse
import hashlib
import http.server
import json
from pathlib import Path
import socketserver
import subprocess
import threading


class ModelHandler(http.server.BaseHTTPRequestHandler):
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
            if not raw.startswith('bytes=') or not raw.endswith('-'):
                self.send_error(416)
                return
            start = int(raw[6:-1])
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
    parser.add_argument('--model', default='build/hxa222-closeout/Qwen3-4B-Instruct-2507-Q4_K_M.gguf')
    parser.add_argument('--sha', default='3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597')
    parser.add_argument('--size', type=int, default=2497281120)
    parser.add_argument('--task-timeout-seconds', type=int, default=300)
    parser.add_argument('--memory-mb', type=int, choices=(4096, 8192), default=8192)
    parser.add_argument('--avd', default='Helix_HXA210_API36')
    parser.add_argument('--emulator-port', type=int, default=5672)
    parser.add_argument('--output', default='build/p4-first-success/api36-developer')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[3]
    model = (root / args.model).resolve()
    assert model.is_file(), model
    assert model.stat().st_size == args.size
    with model.open('rb') as source:
        assert hashlib.file_digest(source, 'sha256').hexdigest() == args.sha
    assert 60 <= args.task_timeout_seconds <= 900
    ModelHandler.model = model
    ModelHandler.requests = []
    with socketserver.ThreadingTCPServer(('127.0.0.1', 0), ModelHandler) as server:
        server.daemon_threads = True
        port = server.server_address[1]
        thread = threading.Thread(target=server.serve_forever, name='p4-model-http', daemon=True)
        thread.start()
        command = [
            'python3', 'scripts/run-owned-emulator.py',
            '--avd', args.avd,
            '--port', str(args.emulator_port),
            '--memory-mb', str(args.memory_mb),
            '--cores', '4',
            '--density-dpi', '400',
            '--apk', 'app/build/outputs/apk/developer/debug/app-developer-debug.apk',
            '--test-apk', 'app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk',
            '--recovery-setup-class', 'com.helix.app.localmodel.FirstSuccessJourneyDeviceTest',
            '--classes', 'com.helix.app.localmodel.FirstSuccessJourneyDeviceTest',
            '--runner', 'com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner',
            '--output', args.output,
            '--timeout', str(args.task_timeout_seconds + 180),
            '--reverse-port', str(port),
            '--clear-app-data',
            '--instrument-arg', f'p4ModelPort={port}',
            '--instrument-arg', f'p4ModelSha={args.sha}',
            '--instrument-arg', f'p4ModelSize={args.size}',
            '--instrument-arg', f'p4TaskTimeoutSeconds={args.task_timeout_seconds}',
            '--after-script', 'scripts/debug/2026-09-28/collect-p4-first-success.py',
            '--raw-results',
        ]
        try:
            subprocess.run(command, cwd=root, check=True, timeout=args.task_timeout_seconds + 600)
        finally:
            server.shutdown()
            thread.join(timeout=10)
    out = root / args.output
    (out / 'http-fixture.json').write_text(json.dumps(ModelHandler.requests, indent=2) + '\n')
    assert ModelHandler.requests and ModelHandler.requests[0]['status'] == 200, ModelHandler.requests
    evidence = (out / 'first-success-evidence.txt').read_text()
    assert 'duplicateSideEffect=false' in evidence, evidence
    print(json.dumps({'requests': ModelHandler.requests, 'modelSha': args.sha, 'modelBytes': args.size}, indent=2))
    print(evidence)


if __name__ == '__main__':
    main()
