#!/usr/bin/env python3
"""Kill a Goal after a real Accessibility UI click with model backfill held."""
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
import xml.etree.ElementTree as ET

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
    services = ""
    result_path = None
    def model(self, request):
        if 'UI_GOAL_KILL' not in json.dumps(request):
            return super().model(request)
        with self.lock:
            type(self).model_requests += 1
        results = [m for m in request.get('messages', []) if m.get('role') == 'tool']
        if not results:
            tool, args = 'ui.snapshot', {}
        else:
            self.result_path.write_text(json.dumps(results[-1], indent=2))
            node = find_button(results[-1].get('content'))
            if results[-1].get('tool_call_id') == 'ui-goal-ui-click':
                type(self).backfills += 1
                self.backfill_seen.set()
                self.release.wait(90)
                return
            assert node is not None, 'Snapshot did not contain the synthetic button'
            tool, args = 'ui.click', {'token': node['token']}
        delta = {'tool_calls': [{'id': 'ui-goal-' + tool.replace('.', '-'), 'index': 0, 'type': 'function',
                                'function': {'name': tool, 'arguments': json.dumps(args)}}]}
        chunks = [{'id': 'fixture', 'object': 'chat.completion.chunk', 'choices': [
            {'index': 0, 'delta': d, 'finish_reason': finish}]} for d, finish in
            [(delta, None), ({}, 'tool_calls')]]
        chunks.append({'id': 'fixture', 'choices': [], 'usage': {
            'prompt_tokens': 10, 'completion_tokens': 2, 'total_tokens': 12}})
        self.payload('text/event-stream', ''.join('data: '+json.dumps(c)+'\n\n' for c in chunks)+'data: [DONE]\n\n')


def find_button(value):
    if isinstance(value, str):
        try:
            return find_button(json.loads(value.removeprefix("[SUCCEEDED] ")))
        except json.JSONDecodeError:
            return None
    if isinstance(value, dict):
        if str(value.get('text', '')).casefold() == 'fixture click' and 'token' in value:
            return value
        value = list(value.values())
    if isinstance(value, list):
        for item in value:
            node = find_button(item)
            if node is not None:
                return node
    return None


def clicks(base):
    xml = subprocess.check_output(base + ['shell', 'run-as', PACKAGE + '.test',
        'cat', 'shared_prefs/ui-goal-kill-counter.xml'], text=True)
    return int(ET.fromstring(xml).find("int[@name='clicks']").get('value'))


def phase(base, boundary, name, output):
    path = output / f'{boundary}-{name}.log'
    cmd = base + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                  'com.helix.app.eval.UiGoalProcessKillDeviceTest',
                  '-e', 'ui.goal.kill.phase', name, '-e', 'ui.goal.kill.port', str(boundary),
                  '-e', 'ui.goal.kill.boundary', 'unsettled' if Fixture.unsettled else 'backfill',
                  PACKAGE + '.test/com.helix.app.HelixAndroidJUnitRunner']
    with path.open('w') as log:
        proc = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT)
        if name == 'prepare':
            deadline = time.monotonic() + 40
            match = None
            enabled = False
            diagnosed = False
            enabled_at = 0
            while time.monotonic() < deadline:
                if not enabled and 'UI_SERVICE_READY' in path.read_text():
                    subprocess.run(base + ['shell', 'am', 'start', '-W', '-n', PACKAGE + '/com.helix.app.MainActivity'], check=True, stdout=subprocess.DEVNULL)
                    subprocess.run(base + ['shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', Fixture.services], check=True)
                    subprocess.run(base + ['shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1'], check=True)
                    enabled = True
                    enabled_at = time.monotonic()
                if enabled and not diagnosed and time.monotonic() - enabled_at > 3:
                    for kind in ['activity', 'accessibility']:
                        data = subprocess.check_output(base + ['shell', 'dumpsys', kind], text=True)
                        (output / (kind + '-during-prepare.txt')).write_text(data)
                    data = subprocess.check_output(base + ['exec-out', 'screencap', '-p'])
                    (output / 'during-prepare.png').write_bytes(data)
                    diagnosed = True
                match = re.search(r'UI_GOAL_KILL_READY pid=(\d+)', path.read_text())
                if match or proc.poll() is not None:
                    break
                time.sleep(.05)
            if not match:
                raise RuntimeError(f'No ready boundary; inspect owned fixture: {path}')
            if not Fixture.unsettled:
                assert Fixture.backfill_seen.wait(5), "Backfill must reach the host before kill"
            assert clicks(base) == 1, "UI click must be durably counted before kill"
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
    old_services = subprocess.check_output(base + ['shell', 'settings', 'get', 'secure',
        'enabled_accessibility_services'], text=True).strip()
    old_enabled = subprocess.check_output(base + ['shell', 'settings', 'get', 'secure',
        'accessibility_enabled'], text=True).strip()
    component = PACKAGE + '/com.helix.tools.automation.HelixAccessibilityService'
    services = sorted(set(filter(None, old_services.split(':') if old_services != 'null' else [])) | {component})
    Fixture.services = ':'.join(services)
    Fixture.result_path = args.output / 'last-synthetic-tool-result.json'
    try:
        records = [phase(base, server.server_port, 'prepare', args.output)]
        if Fixture.unsettled:
            assert Fixture.backfills == 0, 'Unsettled result must not reach the model'
        else:
            assert Fixture.backfill_seen.wait(5), 'No actual tool result backfill received'
        before = Fixture.model_requests
        records += [phase(base, server.server_port, n, args.output) for n in ['recover', 'recover-final']]
        assert Fixture.model_requests == before, 'Startup replayed a model request'
        assert clicks(base) == 1, 'UI action replayed'
        result = dict(serial=args.serial, unsettled=Fixture.unsettled, installedApks=hashes, records=records,
                      modelRequests=before, backfillRequests=Fixture.backfills, uiClicks=clicks(base),
                      scope=('Production action held after execution before result settlement' if Fixture.unsettled else 'Production Goal/ui.click with durable synthetic UI click counter and held model backfill'))
    finally:
        for key, value in [('enabled_accessibility_services', old_services), ('accessibility_enabled', old_enabled)]:
            command = ['delete', 'secure', key] if value in ('null', '') else ['put', 'secure', key, value]
            subprocess.run(base + ['shell', 'settings'] + command, check=True)
        Fixture.release.set()
        server.shutdown()
        server.server_close()
    (args.output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
