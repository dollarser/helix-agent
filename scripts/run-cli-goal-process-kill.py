#!/usr/bin/env python3
"""Kill a dedicated emulator's CLI client owner; reconcile only its original synthetic Runtime Job."""
import argparse
import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import time

from android_process_control import kill_emulator_app, verify_emulator_signal_control


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--successful', action='store_true')
    parser.add_argument('--provider', choices=['CODEX', 'CLAUDE', 'GROK', 'COPILOT'], required=True)
    parser.add_argument('--adb', default=shutil.which('adb'))
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    if not args.adb:
        parser.error('adb required')
    base = [args.adb, '-s', args.serial]
    verify_emulator_signal_control(base)
    args.output.mkdir(parents=True, exist_ok=False)
    hashes = {}
    for pkg, path in [('com.helix.agent.developer', 'app/build/outputs/apk/developer/debug/app-developer-debug.apk'),
        ('com.helix.agent.developer.test', 'app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk'),
        ('com.helix.runtime.cli', 'runtime/cli-app/build/outputs/apk/debug/cli-app-debug.apk')]:
        remote = subprocess.check_output(base + ['shell', 'pm', 'path', pkg], text=True).strip().removeprefix('package:')
        actual = subprocess.check_output(base + ['shell', 'sha256sum', remote], text=True).split()[0]
        assert actual == hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest(), pkg
        hashes[pkg] = actual
    records = []
    job = None
    for phase in ['prepare', 'recover', 'recover-final']:
        log = args.output / f'cli-owner-{phase}.log'
        cmd = base + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
            'com.helix.app.provider.CliGoalProcessKillDeviceTest', '-e', 'cli.owner.phase', phase,
            '-e', 'cli.owner.provider', args.provider, '-e', 'cli.owner.successful', str(args.successful).lower(),
            'com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner']
        with log.open('w') as output:
            proc = subprocess.Popen(cmd, stdout=output, stderr=subprocess.STDOUT)
            if phase == 'prepare':
                deadline = time.monotonic() + 15
                match = None
                while time.monotonic() < deadline:
                    match = re.search(r'CLI_OWNER_KILL_READY pid=(\d+) job=(job_[0-9a-f]{12})', log.read_text())
                    if match or proc.poll() is not None:
                        break
                    time.sleep(.05)
                if not match:
                    raise RuntimeError(f'No ready Job boundary: {log}; inspect owned fixture before continuing')
                pid, job = match.groups()
                runtime = subprocess.check_output(base + ['shell', 'pidof', 'com.helix.runtime.cli'], text=True).split()
                kill_emulator_app(base, 'com.helix.agent.developer', pid)
                proc.wait(timeout=10)
                assert 'shortMsg=Process crashed.' in log.read_text()
                records.append(dict(phase=phase, job=job, mainPid=int(pid), runtimePids=runtime,
                                    signal='SIGKILL', signalAuthority='emulator host su 0'))
            else:
                proc.wait(timeout=15)
                text = log.read_text()
                assert 'OK (1 test)' in text, text
                found = re.search(r'CLI_OWNER_RECOVERED state=(CANCELLED|INTERRUPTED|SUCCEEDED) job=(job_[0-9a-f]{12})', text)
                assert found and found.group(2) == job
                records.append(dict(phase=phase, job=job, state=found.group(1), tests=1))
    result = dict(serial=args.serial, provider=args.provider, successful=args.successful, installedApks=hashes, records=records,
                  scope='Production Goal/Chat/subscription adapter and cross-UID Runtime; debug synthetic model, no accounts or remote model')
    (args.output / 'result.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result), flush=True)


if __name__ == '__main__':
    main()
