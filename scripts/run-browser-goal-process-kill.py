#!/usr/bin/env python3
"""Kill a Goal after a real browser click with navigation and model backfill held."""
import argparse
import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import time
import http.server
import threading

import importlib.util

_model_spec = importlib.util.spec_from_file_location(
    "helix_js_kill_fixture", pathlib.Path(__file__).with_name("run-javascript-process-kill.py"))
_model_module = importlib.util.module_from_spec(_model_spec)
_model_spec.loader.exec_module(_model_module)
ModelFixture = _model_module.Fixture

from android_process_control import kill_emulator_app, verify_emulator_signal_control

PACKAGE = 'com.helix.agent.developer'


class Fixture(ModelFixture):
    unsettled = False
    backfill_seen = threading.Event()
    release = threading.Event()
    backfills = 0
    actions = 0
    action_seen = threading.Event()

    def do_GET(self):
        if self.path == '/action':
            type(self).actions += 1
            self.action_seen.set()
            self.release.wait(90)
            return
        super().do_GET()

    def model(self, request):
        if 'BROWSER_GOAL_KILL' not in json.dumps(request):
            return super().model(request)
        with self.lock:
            type(self).model_requests += 1
        if any(message.get('role') == 'tool' for message in request.get('messages', [])):
            type(self).backfills += 1
            self.backfill_seen.set()
            self.release.wait(90)
            return
        text = json.dumps(request)
        tab = re.search(r'tab=([^\s"\\]+)', text).group(1)
        token = re.search(r'token=([^\s"\\]+)', text).group(1)
        arguments = json.dumps({'tabId': tab, 'token': token})
        delta = {'tool_calls': [{'id': 'browser-goal-fixture', 'index': 0, 'type': 'function',
                                'function': {'name': 'browser.click', 'arguments': arguments}}]}
        chunks = [{'id': 'fixture', 'object': 'chat.completion.chunk', 'choices': [
            {'index': 0, 'delta': d, 'finish_reason': finish}]} for d, finish in
            [(delta, None), ({}, 'tool_calls')]]
        chunks.append({'id': 'fixture', 'choices': [], 'usage': {
            'prompt_tokens': 10, 'completion_tokens': 2, 'total_tokens': 12}})
        self.payload('text/event-stream', ''.join('data: '+json.dumps(c)+'\n\n' for c in chunks)+'data: [DONE]\n\n')


def phase(base, boundary, name, output):
    path = output / f'{boundary}-{name}.log'
    cmd = base + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                  'com.helix.app.eval.BrowserGoalProcessKillDeviceTest',
                  '-e', 'browser.goal.kill.phase', name, '-e', 'browser.goal.kill.port', str(boundary),
                  '-e', 'browser.goal.kill.boundary', 'unsettled' if Fixture.unsettled else 'backfill',
                  PACKAGE + '.test/com.helix.app.HelixAndroidJUnitRunner']
    with path.open('w') as log:
        proc = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT)
        if name == 'prepare':
            deadline = time.monotonic() + 40
            match = None
            while time.monotonic() < deadline:
                match = re.search(r'BROWSER_GOAL_KILL_READY pid=(\d+)', path.read_text())
                if match or proc.poll() is not None:
                    break
                time.sleep(.05)
            if not match:
                raise RuntimeError(f'No ready boundary; inspect owned fixture: {path}')
            if not Fixture.unsettled:
                assert Fixture.backfill_seen.wait(5), "Backfill must reach the host before kill"
            assert Fixture.action_seen.wait(5), "No actual browser navigation reached host"
            kill_emulator_app(base, PACKAGE, match.group(1))
            proc.wait(timeout=15)
            assert 'shortMsg=Process crashed.' in path.read_text(), path.read_text()
            return dict(phase=name, pid=int(match.group(1)), signal='SIGKILL')
        proc.wait(timeout=40)
        assert 'OK (1 test)' in path.read_text(), path.read_text()
        return dict(phase=name, tests=1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', default=shutil.which('adb'))
    parser.add_argument('--output', type=pathlib.Path, required=True)
    parser.add_argument('--unsettled', action='store_true')
    args = parser.parse_args()
    Fixture.unsettled = args.unsettled
    if not args.adb:
        parser.error('adb is required')
    base = [args.adb, '-s', args.serial]
    verify_emulator_signal_control(base)
    args.output.mkdir(parents=True, exist_ok=False)
    hashes = {}
    for package, relative in [
        (PACKAGE, 'app/build/outputs/apk/developer/debug/app-developer-debug.apk'),
        (PACKAGE + '.test', 'app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk'),
    ]:
        remote = subprocess.check_output(base + ['shell', 'pm', 'path', package], text=True).strip().removeprefix('package:')
        actual = subprocess.check_output(base + ['shell', 'sha256sum', remote], text=True).split()[0]
        assert actual == hashlib.sha256(pathlib.Path(relative).read_bytes()).hexdigest(), package
        hashes[package] = actual
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Fixture)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        records = [phase(base, server.server_port, 'prepare', args.output)]
        if Fixture.unsettled:
            assert Fixture.backfills == 0, 'Unsettled result must not reach the model'
        else:
            assert Fixture.backfill_seen.wait(5), 'No actual tool result backfill received'
        before = Fixture.model_requests
        records += [phase(base, server.server_port, n, args.output) for n in ['recover', 'recover-final']]
        assert Fixture.model_requests == before, 'Startup replayed a model request'
        assert Fixture.actions == 1, 'Browser action replayed'
        result = dict(serial=args.serial, unsettled=Fixture.unsettled, installedApks=hashes, records=records,
                      modelRequests=before, backfillRequests=Fixture.backfills, browserActions=Fixture.actions,
                      scope=('Production action held after execution before result settlement' if Fixture.unsettled else 'Production Goal/browser.click with actual held navigation and model backfill; no action replay'))
    finally:
        Fixture.release.set()
        server.shutdown()
        server.server_close()
    (args.output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
