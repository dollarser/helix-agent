#!/usr/bin/env python3
"""Kill a dedicated emulator app during an approved A2A HTTP call; verify no startup replay."""
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
import uuid

from android_process_control import kill_emulator_app, verify_emulator_signal_control


class Fixture(http.server.BaseHTTPRequestHandler):
    calls = 0
    boundary = "poll"
    call_id = None
    model_requests = 0
    started = threading.Event()
    disconnected = threading.Event()
    lock = threading.Lock()

    def log_message(self, *_args):
        pass

    def payload(self, kind, value):
        data = value.encode()
        self.send_response(200)
        self.send_header('Content-Type', kind)
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == '/kill/card':
            card = {'name': 'Helix A2A kill fixture', 'description': 'Synthetic bounded task',
                'version': '1', 'capabilities': {'streaming': False},
                'supportedInterfaces': [{'url': f'http://127.0.0.1:{self.server.server_port}/a2a',
                    'protocolBinding': 'JSONRPC', 'protocolVersion': '1.0'}],
                'defaultInputModes': ['text/plain'], 'defaultOutputModes': ['text/plain'],
                'skills': [{'id': 'fixture_task', 'name': 'Fixture task', 'description': 'Synthetic task', 'tags': ['fixture']}]}
            self.payload('application/json', json.dumps(card))
        else:
            self.payload('application/json', json.dumps({'data': [{'id': 'fixture-model-a'}]}))

    def do_DELETE(self):
        self.send_response(200)
        self.end_headers()

    def do_POST(self):
        length = int(self.headers.get('Content-Length', '0'))
        if not 0 < length <= 1048576:
            self.send_error(413)
            return
        request = json.loads(self.rfile.read(length))
        if self.path == '/a2a':
            self.a2a(request)
        else:
            self.model(request)

    polls = 0

    def a2a(self, request):
        method = request['method']
        if method == 'SendMessage':
            with self.lock:
                type(self).calls += 1
            if self.boundary == 'send':
                self.started.set()
                self.connection.settimeout(40)
                if self.connection.recv(1) == b'':
                    self.disconnected.set()
                return
            state = 'TASK_STATE_WORKING'
        elif method == 'GetTask':
            assert request['params']['id'] == 'task-kill', request['params'].keys()
            with self.lock:
                type(self).polls += 1
                poll = type(self).polls
            if poll == 1:
                self.started.set()
                self.connection.settimeout(40)
                if self.connection.recv(1) == b'':
                    self.disconnected.set()
                return
            state = 'TASK_STATE_COMPLETED'
        else:
            raise AssertionError('Unexpected A2A method: '+method)
        task = {'id': 'task-kill', 'contextId': 'context-kill', 'status': {'state': state,
            'message': {'role': 'ROLE_AGENT', 'parts': [{'text': 'SYNTHETIC_A2A_KILL_OK'}]}}}
        self.payload('application/json', json.dumps({'jsonrpc': '2.0', 'id': request['id'], 'result': {'task': task}}))

    def model(self, request):
        with self.lock:
            type(self).model_requests += 1
        match = re.search(r'A2A_KILL_TOOL=([^\s"\\]+)', json.dumps(request))
        tool = match.group(1) if match else 'echo'
        args = '{"task":"kill","stream":false}' if match else '{"text":"probe"}'
        has_tools = bool(request.get('tools'))
        delta = {'tool_calls': [{'id': self.call_id or 'fixture-'+uuid.uuid4().hex, 'index': 0, 'type': 'function',
                  'function': {'name': tool, 'arguments': args}}]} if has_tools else {'content': 'ok'}
        chunks = [{'id': 'fixture', 'object': 'chat.completion.chunk', 'choices': [
            {'index': 0, 'delta': d, 'finish_reason': finish}]} for d, finish in
            [(delta, None), ({}, 'tool_calls' if has_tools else 'stop')]]
        chunks.append({'id': 'fixture', 'choices': [], 'usage': {'prompt_tokens': 10, 'completion_tokens': 2, 'total_tokens': 12}})
        self.payload('text/event-stream', ''.join('data: '+json.dumps(c)+'\n\n' for c in chunks)+'data: [DONE]\n\n')


def phase(base, port, name, output):
    path = output / f'a2a-kill-{name}.log'
    cmd = base + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
        'com.helix.app.eval.A2aProcessKillDeviceTest', '-e', 'a2a.kill.port', str(port),
        '-e', 'a2a.kill.phase', name, '-e', 'a2a.kill.boundary', Fixture.boundary, 'com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner']
    with path.open('w') as log:
        proc = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT)
        if name == 'prepare':
            deadline = time.monotonic() + 30
            match = None
            while time.monotonic() < deadline:
                match = re.search(r'A2A_KILL_READY pid=(\d+)', path.read_text())
                if (match and Fixture.started.is_set()) or proc.poll() is not None:
                    break
                time.sleep(.05)
            if not match or not Fixture.started.is_set():
                raise RuntimeError(f'No live A2A ready boundary: {path}; inspect owned fixture before cleanup')
            assert Fixture.calls == 1
            kill_emulator_app(base, 'com.helix.agent.developer', match.group(1))
            proc.wait(timeout=15)
            assert 'shortMsg=Process crashed.' in path.read_text()
            assert Fixture.disconnected.wait(5), 'No remote socket EOF'
            return dict(phase=name, pid=int(match.group(1)), signal='SIGKILL',
                        signalAuthority='emulator host su 0', socketEOF=True)
        proc.wait(timeout=40)
        assert 'OK (1 test)' in path.read_text(), path.read_text()
        return dict(phase=name, tests=1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--boundary', choices=['send', 'poll'], default='poll')
    parser.add_argument('--call-id', help='Explicit synthetic provider ID for duplicate-ID regression')
    parser.add_argument('--adb', default=shutil.which('adb'))
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    if not args.adb:
        parser.error('adb is required')
    Fixture.call_id = args.call_id
    Fixture.boundary = args.boundary
    base = [args.adb, '-s', args.serial]
    verify_emulator_signal_control(base)
    args.output.mkdir(parents=True, exist_ok=False)
    hashes = {}
    for package, relative in [('com.helix.agent.developer', 'app/build/outputs/apk/developer/debug/app-developer-debug.apk'),
        ('com.helix.agent.developer.test', 'app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk')]:
        remote = subprocess.check_output(base + ['shell', 'pm', 'path', package], text=True).strip().removeprefix('package:')
        actual = subprocess.check_output(base + ['shell', 'sha256sum', remote], text=True).split()[0]
        assert actual == hashlib.sha256(pathlib.Path(relative).read_bytes()).hexdigest(), package
        hashes[package] = actual
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Fixture)
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    reverse = f'tcp:{server.server_port}'
    subprocess.run(base + ['reverse', reverse, reverse], check=True)
    try:
        records = [phase(base, server.server_port, 'prepare', args.output)]
        before = (Fixture.calls, Fixture.model_requests)
        for name in ['recover', 'recover-final']:
            records.append(phase(base, server.server_port, name, args.output))
            assert before == (Fixture.calls, Fixture.model_requests), 'Startup replayed a request'
            if name == 'recover':
                assert Fixture.polls == (0 if args.boundary == "send" else 1), 'Startup queried the remote task without explicit reconciliation'
        assert Fixture.polls == (0 if args.boundary == 'send' else 2), 'Unexpected remote task queries'
        result = dict(serial=args.serial, boundary=args.boundary, records=records, installedApks=hashes,
                      sendMessages=Fixture.calls, getTasks=Fixture.polls, reconciledTaskId=None if args.boundary == "send" else "task-kill",
                      reconciliationOutcome="REVIEW_REQUIRED" if args.boundary == "send" else "COMPLETED", modelRequests=Fixture.model_requests, startupReplay=False,
                      scope='Scripted model; production Goal/Chat/Dispatcher/approval/A2A HTTP/Room startup recovery and explicit reconciliation boundary')
        (args.output / 'result.json').write_text(json.dumps(result, indent=2)+'\n')
        print(json.dumps(result), flush=True)
    finally:
        subprocess.run(base + ['reverse', '--remove', reverse], check=True)
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


if __name__ == '__main__':
    main()
