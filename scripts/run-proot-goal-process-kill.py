#!/usr/bin/env python3
"""Kill a dedicated emulator app during approved isolated PRoot execution; verify no startup replay."""
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
    successful = False
    offline_final = False
    result_boundary = "none"
    job = None
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
        match = re.search(r'PROOT_GOAL_KILL_TOOL=([^\s"\\]+)', json.dumps(request))
        tool = match.group(1) if match else 'echo'
        script = 'echo PROOT_GOAL_STARTED > /workspace/started.txt; /bin/sleep 20'
        if self.successful:
            script = script.replace('/bin/sleep 20', '/bin/sleep 8' if getattr(self, 'delivery_failure', False) else '/bin/sleep 2') + '; echo PROOT_RESULT_READY'
        args = json.dumps({'script': script, 'timeoutSeconds': 30}) if match else '{"text":"probe"}'
        has_tools = bool(request.get('tools'))
        if getattr(self, 'normal_completion', False) and any(m.get('role') == 'tool' for m in request.get('messages', [])):
            has_tools = False
        delta = {'tool_calls': [{'id': self.call_id or 'fixture-'+uuid.uuid4().hex, 'index': 0, 'type': 'function',
                  'function': {'name': tool, 'arguments': args}}]} if has_tools else {'content': 'ok'}
        chunks = [{'id': 'fixture', 'object': 'chat.completion.chunk', 'choices': [
            {'index': 0, 'delta': d, 'finish_reason': finish}]} for d, finish in
            [(delta, None), ({}, 'tool_calls' if has_tools else 'stop')]]
        chunks.append({'id': 'fixture', 'choices': [], 'usage': {'prompt_tokens': 10, 'completion_tokens': 2, 'total_tokens': 12}})
        self.payload('text/event-stream', ''.join('data: '+json.dumps(c)+'\n\n' for c in chunks)+'data: [DONE]\n\n')


def commit_before_kill(base, pid, job, output):
    live = subprocess.check_output(base + ['shell', 'pidof', 'com.helix.agent.developer'], text=True).split()
    assert pid in live, 'Ready PID is no longer the test App'
    subprocess.run(base + ['shell', 'su', '0', 'kill', '-STOP', pid], check=True, timeout=5)
    killed = False
    try:
        deadline = time.monotonic() + 10
        record = {}
        while time.monotonic() < deadline:
            saved = subprocess.run(base + ['shell', 'run-as', 'com.helix.runtime.proot', 'cat',
                f'files/runtime/jobs/{job}/record.json'], capture_output=True, text=True)
            record = json.loads(saved.stdout) if saved.returncode == 0 else {}
            if record.get('state') == 'SUCCEEDED':
                break
            time.sleep(.1)
        assert record.get('state') == 'SUCCEEDED' and record.get('terminalCommit'), record
        (output / 'terminal-before-kill.json').write_text(json.dumps(record, indent=2)+'\n')
        kill_emulator_app(base, 'com.helix.agent.developer', pid)
        killed = True
        return record['terminalCommit']
    finally:
        if not killed:
            live = subprocess.run(base + ['shell', 'pidof', 'com.helix.agent.developer'], capture_output=True, text=True)
            if pid in live.stdout.split():
                subprocess.run(base + ['shell', 'su', '0', 'kill', '-CONT', pid], check=True, timeout=5)


def phase(base, port, name, output):
    path = output / f'proot-goal-kill-{name}.log'
    cmd = base + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
        'com.helix.app.eval.ProotGoalProcessKillDeviceTest', '-e', 'proot.goal.kill.port', str(port),
        '-e', 'proot.goal.kill.phase', name, '-e', 'proot.goal.result.boundary', Fixture.result_boundary, '-e', 'proot.goal.kill.offline', str(Fixture.offline_final and name == 'recover-final').lower(), '-e', 'proot.goal.kill.successful', str(Fixture.successful).lower(), '-e', 'proot.goal.kill.boundary', Fixture.boundary, 'com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner']
    with path.open('w') as log:
        proc = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT)
        if name == 'result-boundary':
            deadline = time.monotonic() + 25
            match = None
            while time.monotonic() < deadline:
                match = re.search(r'PROOT_RESULT_BOUNDARY_READY pid=(\d+)', path.read_text())
                if match or proc.poll() is not None:
                    break
                time.sleep(.05)
            assert match, f'Result boundary was not reached: {path}'
            record = json.loads(subprocess.check_output(base + ['shell', 'run-as', 'com.helix.runtime.proot', 'cat',
                f'files/runtime/jobs/{Fixture.job}/record.json'], text=True))
            acknowledged = record.get('reconciledAtEpochMs') is not None
            assert acknowledged == (Fixture.result_boundary == 'acknowledged'), record
            (output / 'result-boundary-record.json').write_text(json.dumps(record, indent=2)+'\n')
            kill_emulator_app(base, 'com.helix.agent.developer', match.group(1))
            proc.wait(timeout=15)
            assert 'shortMsg=Process crashed.' in path.read_text()
            return dict(phase=name, boundary=Fixture.result_boundary, pid=int(match.group(1)), signal='SIGKILL')
        if name == 'prepare':
            deadline = time.monotonic() + 30
            match = None
            while time.monotonic() < deadline:
                match = re.search(r'PROOT_GOAL_KILL_READY pid=(\d+) job=(job_[0-9a-f]+)', path.read_text())
                if match or proc.poll() is not None:
                    break
                time.sleep(.05)
            if not match:
                raise RuntimeError(f'No live PRoot ready boundary: {path}; inspect owned fixture before cleanup')
            job = match.group(2)
            Fixture.job = job
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                started = subprocess.run(base + ['shell', 'run-as', 'com.helix.runtime.proot', 'cat',
                    f'files/runtime/jobs/{job}/workspace/started.txt'], capture_output=True, text=True)
                if started.returncode == 0 and started.stdout.strip() == 'PROOT_GOAL_STARTED':
                    break
                time.sleep(.05)
            assert started.returncode == 0 and started.stdout.strip() == 'PROOT_GOAL_STARTED'
            committed = None
            if Fixture.successful:
                committed = commit_before_kill(base, match.group(1), job, output)
            else:
                kill_emulator_app(base, 'com.helix.agent.developer', match.group(1))
            proc.wait(timeout=15)
            assert 'shortMsg=Process crashed.' in path.read_text()
            expected = 'SUCCEEDED' if Fixture.successful else 'CANCELLED'
            deadline = time.monotonic() + (35 if Fixture.successful else 10)
            while time.monotonic() < deadline:
                saved = subprocess.run(base + ['shell', 'run-as', 'com.helix.runtime.proot', 'cat',
                    f'files/runtime/jobs/{job}/record.json'], capture_output=True, text=True)
                record = json.loads(saved.stdout) if saved.returncode == 0 else {}
                if record.get('state') == expected:
                    break
                time.sleep(.2)
            assert record.get('state') == expected, record
            if committed:
                assert record.get('terminalCommit') == committed, record
            return dict(phase=name, pid=int(match.group(1)), signal='SIGKILL',
                        signalAuthority='emulator host su 0', job=job, guestStarted=True, terminalBeforeRecovery=record.get("state"), terminalCommitBeforeKill=committed)
        proc.wait(timeout=40)
        assert 'OK (1 test)' in path.read_text(), path.read_text()
        return dict(phase=name, tests=1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--result-boundary', choices=['none', 'persisted', 'acknowledged'], default='none')
    parser.add_argument('--offline-final', action='store_true', help='Disable Runtime during final local result read')
    parser.add_argument('--successful', action='store_true', help='SIGSTOP App until original terminal commit, then SIGKILL before consumption')
    parser.add_argument('--boundary', choices=['execution'], default='execution')
    parser.add_argument('--after-recovery', choices=['none'], default='none')
    parser.add_argument('--call-id', help='Explicit synthetic provider ID for duplicate-ID regression')
    parser.add_argument('--adb', default=shutil.which('adb'))
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    if not args.adb:
        parser.error('adb is required')
    if args.after_recovery == "deny" and args.boundary != "approval":
        parser.error("deny requires approval boundary")
    if args.offline_final and not args.successful:
        parser.error("offline-final requires successful")
    if args.result_boundary != "none" and not args.successful:
        parser.error("result-boundary requires successful")
    Fixture.result_boundary = args.result_boundary
    Fixture.offline_final = args.offline_final
    Fixture.successful = args.successful
    Fixture.after_recovery = args.after_recovery
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
    try:
        records = [phase(base, server.server_port, 'prepare', args.output)]
        before = Fixture.model_requests
        if args.result_boundary != 'none':
            records.append(phase(base, server.server_port, 'result-boundary', args.output))
            assert before == Fixture.model_requests, 'Boundary recovery replayed a request'
        recovery_phases = ['recover', 'resolve-denial', 'verify-denial'] if args.after_recovery == 'deny' else ['recover', 'recover-final']
        for name in recovery_phases:
            offline = args.offline_final and name == 'recover-final'
            disabled_by_test = False
            try:
                if offline:
                    state = subprocess.check_output(base + ['shell', 'dumpsys', 'package', 'com.helix.runtime.proot'], text=True)
                    assert re.search(r'User 0:.*enabled=0', state), 'Runtime must start in the default enabled state'
                    subprocess.run(base + ['shell', 'pm', 'disable-user', '--user', '0', 'com.helix.runtime.proot'], check=True, capture_output=True)
                    disabled_by_test = True
                    disabled = subprocess.check_output(base + ['shell', 'pm', 'list', 'packages', '-d', 'com.helix.runtime.proot'], text=True)
                    assert 'package:com.helix.runtime.proot' in disabled
                    (args.output / 'runtime-disabled.txt').write_text(disabled)
                records.append(phase(base, server.server_port, name, args.output))
                assert before == Fixture.model_requests, 'Startup replayed a request'
            finally:
                if disabled_by_test:
                    subprocess.run(base + ['shell', 'pm', 'default-state', '--user', '0', 'com.helix.runtime.proot'], check=True, capture_output=True)
        result = dict(serial=args.serial, resultBoundary=args.result_boundary, offlineFinal=args.offline_final, successful=args.successful, diagnosticOnly=False, boundary=args.boundary, afterRecovery=args.after_recovery, records=records, installedApks=hashes,
                      modelRequests=Fixture.model_requests, startupReplay=False,
                      scope='Scripted model; production Goal/Chat/Dispatcher/approval/PRoot guest/Room recovery and explicit original Job reconciliation')
        (args.output / 'result.json').write_text(json.dumps(result, indent=2)+'\n')
        print(json.dumps(result), flush=True)
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


if __name__ == '__main__':
    main()
