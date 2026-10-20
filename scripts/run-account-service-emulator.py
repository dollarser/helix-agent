#!/usr/bin/env python3
"""Opt-in real account checks on a disposable emulator, using an ignored owner-supplied profile."""
import argparse
import http.server
import json
import os
from pathlib import Path
import subprocess
import sys
import threading


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile', required=True, type=Path)
    parser.add_argument('--channel', choices=('consumer', 'developer'), required=True)
    parser.add_argument('--avd', required=True)
    parser.add_argument('--port', type=int, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--with-speech', action='store_true')
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    if args.profile.is_symlink():
        raise ValueError("Credential profile must not be a symlink")
    output = args.output.resolve()
    if not output.is_relative_to(root / "build") or output.exists():
        raise ValueError("Use a new evidence directory under this repository build/")
    profile_path = args.profile.resolve()
    if not profile_path.is_file():
        raise ValueError('A regular local credential profile is required')
    if os.name == 'posix' and profile_path.stat().st_mode & 0o077:
        raise ValueError('Credential profile must be private (chmod 600)')
    if subprocess.run(['git', 'check-ignore', '-q', '--', str(profile_path)], cwd=root).returncode != 0:
        raise ValueError('Credential profile must be Git-ignored')
    if subprocess.check_output(['git', 'ls-files', '--', str(profile_path)], cwd=root).strip():
        raise ValueError('Credential profile must never be tracked')
    profile = json.loads(profile_path.read_text())
    if set(profile) != {'email', 'password'} or not all(isinstance(v, str) and v for v in profile.values()):
        raise ValueError('Profile requires nonempty email and password strings')
    served = threading.Event()

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path != '/one-shot-credentials' or served.is_set():
                self.send_error(404)
                return
            payload = json.dumps(profile).encode()
            served.set()
            profile.clear()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, *_args):
            pass  # Never print credential traffic.

    server = http.server.HTTPServer(('127.0.0.1', 19091), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    package = 'com.helix.agent' + ('.developer' if args.channel == 'developer' else '')
    test_class = 'com.helix.app.account.AccountServiceDeviceTest'
    command = [sys.executable, str(root / 'scripts/run-owned-emulator.py'),
               '--avd', args.avd, '--port', str(args.port),
               '--apk', str(root / f'app/build/outputs/apk/{args.channel}/debug/app-{args.channel}-debug.apk'),
               '--test-apk', str(root / f'app/build/outputs/apk/androidTest/{args.channel}/debug/app-{args.channel}-debug-androidTest.apk'),
               '--classes', test_class + ',com.helix.app.ui.AccountLoginFormDeviceTest,com.helix.app.voice.CloudSpeechDeviceTest#incrementalCloudOutputStopsPermanentlyForThatAnswer',
               '--recovery-setup-class', test_class,
               '--runner', package + '.test/com.helix.app.HelixAndroidJUnitRunner',
               '--output', str(args.output), '--timeout', '240', '--reverse-port', '19091',
               '--clear-app-data', '--raw-results',
               '--instrument-arg', 'accountAcceptance=true',
               '--instrument-arg', 'accountCredentialBridge=http://127.0.0.1:19091/one-shot-credentials',
               '--after-script', str(root / 'scripts/collect-account-service-evidence.py')]
    if args.with_speech:
        command += ['--instrument-arg', 'accountSpeechAcceptance=true',
                    '--instrument-arg', 'speechUseAccountLogin=true',
                    '--instrument-arg', 'speechTtsUrl=https://tts.sunlingzhang.com/v1/audio/speech',
                    '--instrument-arg', 'speechAsrUrl=https://asr.sunlingzhang.com/v1/audio/transcriptions']
    try:
        result = subprocess.run(command, cwd=root)
        if not served.is_set():
            print('Account credential bridge was not consumed; real login was not reached.', file=sys.stderr)
        return result.returncode
    finally:
        profile.clear()
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


if __name__ == '__main__':
    raise SystemExit(main())
