#!/usr/bin/env python3
"""Exercise real atomic file publication SIGKILL boundaries on a dedicated emulator."""
import argparse
import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import time

from android_process_control import kill_emulator_app, verify_emulator_signal_control

PACKAGE = 'com.helix.agent.developer'


def phase(base, boundary, name, output):
    path = output / f'{boundary}-{name}.log'
    cmd = base + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                  'com.helix.app.chat.FilePublishProcessKillDeviceTest',
                  '-e', 'file.kill.phase', name, '-e', 'file.kill.boundary', boundary,
                  PACKAGE + '.test/com.helix.app.HelixAndroidJUnitRunner']
    with path.open('w') as log:
        proc = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT)
        if name == 'prepare':
            deadline = time.monotonic() + 40
            match = None
            while time.monotonic() < deadline:
                match = re.search(r'FILE_KILL_READY pid=(\d+)', path.read_text())
                if match or proc.poll() is not None:
                    break
                time.sleep(.05)
            if not match:
                raise RuntimeError(f'No ready boundary; inspect owned fixture: {path}')
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
    args = parser.parse_args()
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
    records = []
    for boundary in ['temporary', 'published']:
        records.append(dict(boundary=boundary, phases=[
            phase(base, boundary, name, args.output)
            for name in ['prepare', 'recover', 'recover-final']]))
    result = dict(serial=args.serial, installedApks=hashes, records=records,
                  scope='Actual atomic stream publication and explicit orphan cleanup; no Goal/ToolCall acceptance')
    (args.output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
