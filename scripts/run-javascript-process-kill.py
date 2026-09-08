#!/usr/bin/env python3
"""Kill a dedicated emulator app during approved isolated JavaScript execution; verify no startup replay."""
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
    boundary = "execution"
    after_recovery = "none"
    call_id = None
    model_requests = 0
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
        if self.path == '/js':
            self.send_error(405)
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
        self.model(request)

    def model(self, request):
        with self.lock:
            type(self).model_requests += 1
        match = re.search(r'JS_KILL_TOOL=([^\s"\\]+)', json.dumps(request))
        tool = match.group(1) if match else 'echo'
        args = json.dumps({'code': 'while (true) {}'}) if match else '{"text":"probe"}'
        has_tools = bool(request.get('tools'))
        delta = {'tool_calls': [{'id': self.call_id or 'fixture-'+uuid.uuid4().hex, 'index': 0, 'type': 'function',
                  'function': {'name': tool, 'arguments': args}}]} if has_tools else {'content': 'ok'}
        chunks = [{'id': 'fixture', 'object': 'chat.completion.chunk', 'choices': [
            {'index': 0, 'delta': d, 'finish_reason': finish}]} for d, finish in
            [(delta, None), ({}, 'tool_calls' if has_tools else 'stop')]]
        chunks.append({'id': 'fixture', 'choices': [], 'usage': {'prompt_tokens': 10, 'completion_tokens': 2, 'total_tokens': 12}})
        self.payload('text/event-stream', ''.join('data: '+json.dumps(c)+'\n\n' for c in chunks)+'data: [DONE]\n\n')


def workers(base):
    lines = subprocess.check_output(base + ['shell', 'ps', '-A', '-o', 'PID,NAME'], text=True).splitlines()
    return [int(line.split()[0]) for line in lines if len(line.split()) == 2 and
            line.split()[1].startswith('com.helix.agent.developer:helix_js')]


def cpu_sample(base, pid):
    tids = subprocess.check_output(base + ['shell', 'su', '0', 'ls', f'/proc/{pid}/task'], text=True).split()
    for tid in tids:
        assert tid.isdigit()
        stat = subprocess.check_output(base + ['shell', 'su', '0', 'cat', f'/proc/{pid}/task/{tid}/stat'], text=True)
        if 'helix-js-exec' in stat:
            fields = stat.rsplit(')', 1)[1].split()
            return int(tid), int(fields[11]) + int(fields[12])
    return None


def await_executing_worker(base):
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        live = workers(base)
        if len(live) == 1:
            first = cpu_sample(base, live[0])
            if first:
                time.sleep(.5)
                second = cpu_sample(base, live[0])
                if second and first[0] == second[0] and second[1] - first[1] >= 5:
                    return dict(pid=live[0], tid=first[0], cpuTicksBefore=first[1], cpuTicksAfter=second[1])
        time.sleep(.05)
    raise RuntimeError('No isolated execution-thread CPU activity before kill')


def phase(base, port, name, output):
    path = output / f'js-kill-{name}.log'
    cmd = base + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
        'com.helix.app.eval.JavascriptProcessKillDeviceTest', '-e', 'js.kill.port', str(port),
        '-e', 'js.kill.phase', name, '-e', 'js.kill.boundary', Fixture.boundary, 'com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner']
    with path.open('w') as log:
        proc = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT)
        if name == 'prepare':
            deadline = time.monotonic() + 30
            match = None
            while time.monotonic() < deadline:
                match = re.search(r'JS_KILL_READY pid=(\d+)', path.read_text())
                if match or proc.poll() is not None:
                    break
                time.sleep(.05)
            if not match:
                raise RuntimeError(f'No live JavaScript ready boundary: {path}; inspect owned fixture before cleanup')
            if Fixture.boundary == 'approval':
                assert not workers(base), 'Worker started before approval'
                worker = None
            else:
                worker = await_executing_worker(base)
            kill_emulator_app(base, 'com.helix.agent.developer', match.group(1))
            proc.wait(timeout=15)
            assert 'shortMsg=Process crashed.' in path.read_text()
            deadline = time.monotonic() + 15
            while workers(base) and time.monotonic() < deadline:
                time.sleep(.1)
            assert not workers(base), 'Isolated worker remained after the owner process died'
            return dict(phase=name, pid=int(match.group(1)), signal='SIGKILL',
                        signalAuthority='emulator host su 0', worker=worker, workerExited=worker is not None, workerAbsentAfterKill=True)
        proc.wait(timeout=40)
        assert 'OK (1 test)' in path.read_text(), path.read_text()
        return dict(phase=name, tests=1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--boundary', choices=['execution', 'approval'], default='execution')
    parser.add_argument('--after-recovery', choices=['none', 'deny'], default='none')
    parser.add_argument('--call-id', help='Explicit synthetic provider ID for duplicate-ID regression')
    parser.add_argument('--adb', default=shutil.which('adb'))
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    if not args.adb:
        parser.error('adb is required')
    if args.after_recovery == "deny" and args.boundary != "approval":
        parser.error("deny requires approval boundary")
    Fixture.after_recovery = args.after_recovery
    Fixture.call_id = args.call_id
    Fixture.boundary = args.boundary
    base = [args.adb, '-s', args.serial]
    verify_emulator_signal_control(base)
    assert not workers(base), "Existing isolated worker must be inspected first"
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
    try:
        records = [phase(base, server.server_port, 'prepare', args.output)]
        before = Fixture.model_requests
        recovery_phases = ['recover', 'resolve-denial', 'verify-denial'] if args.after_recovery == 'deny' else ['recover', 'recover-final']
        for name in recovery_phases:
            records.append(phase(base, server.server_port, name, args.output))
            assert before == Fixture.model_requests, 'Startup replayed a request'
            assert not workers(base), 'Startup recreated an isolated worker'
        result = dict(serial=args.serial, boundary=args.boundary, afterRecovery=args.after_recovery, records=records, installedApks=hashes,
                      modelRequests=Fixture.model_requests, startupReplay=False,
                      scope='Scripted model; production Goal/Chat/Dispatcher/approval/isolated QuickJS/Room startup recovery')
        (args.output / 'result.json').write_text(json.dumps(result, indent=2)+'\n')
        print(json.dumps(result), flush=True)
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


if __name__ == '__main__':
    main()
