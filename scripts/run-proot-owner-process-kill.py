#!/usr/bin/env python3
"""Kill a dedicated emulator's PRoot client owner; reconcile only its original synthetic Runtime Job."""
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
        ('com.helix.runtime.proot', 'runtime/proot-app/build/outputs/apk/debug/proot-app-debug.apk')]:
        remote = subprocess.check_output(base + ['shell', 'pm', 'path', pkg], text=True).strip().removeprefix('package:')
        actual = subprocess.check_output(base + ['shell', 'sha256sum', remote], text=True).split()[0]
        assert actual == hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest(), pkg
        hashes[pkg] = actual
    records = []
    job = None
    for phase in ['prepare', 'recover', 'recover-final']:
        log = args.output / f'proot-owner-{phase}.log'
        cmd = base + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
            'com.helix.app.proot.ProotOwnerProcessKillDeviceTest', '-e', 'proot.owner.phase', phase,
            'com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner']
        with log.open('w') as output:
            proc = subprocess.Popen(cmd, stdout=output, stderr=subprocess.STDOUT)
            if phase == 'prepare':
                deadline = time.monotonic() + 15
                match = None
                while time.monotonic() < deadline:
                    match = re.search(r'PROOT_OWNER_KILL_READY pid=(\d+) job=(job_[0-9a-f]{12})', log.read_text())
                    if match or proc.poll() is not None:
                        break
                    time.sleep(.05)
                if not match:
                    raise RuntimeError(f'No ready Job boundary: {log}; inspect owned fixture before continuing')
                pid, job = match.groups()
                started_path = f'files/runtime/jobs/{job}/workspace/started.txt'
                started = None
                until = time.monotonic() + 5
                while time.monotonic() < until:
                    started = subprocess.run(base + ['shell', 'run-as', 'com.helix.runtime.proot', 'cat', started_path], capture_output=True, text=True)
                    if started.returncode == 0 and started.stdout.strip() == 'PROOT_OWNER_STARTED':
                        break
                    time.sleep(.05)
                assert started and started.returncode == 0 and started.stdout.strip() == 'PROOT_OWNER_STARTED', 'Guest shell did not write its start marker'
                runtime = subprocess.check_output(base + ['shell', 'pidof', 'com.helix.runtime.proot'], text=True).split()
                kill_emulator_app(base, 'com.helix.agent.developer', pid)
                proc.wait(timeout=10)
                assert 'shortMsg=Process crashed.' in log.read_text()
                records.append(dict(phase=phase, job=job, mainPid=int(pid), runtimePids=runtime,
                                    guestStarted=True, signal='SIGKILL', signalAuthority='emulator host su 0'))
            else:
                proc.wait(timeout=15)
                text = log.read_text()
                assert 'OK (1 test)' in text, text
                found = re.search(r'PROOT_OWNER_RECOVERED state=(CANCELLED|ORPHANED) job=(job_[0-9a-f]{12})', text)
                assert found and found.group(2) == job
                records.append(dict(phase=phase, job=job, state=found.group(1), tests=1))
    result = dict(serial=args.serial, installedApks=hashes, records=records,
                  scope='Production cross-UID PRoot client and guest shell; no model service or ChatService/Goal binding')
    (args.output / 'result.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result), flush=True)


if __name__ == '__main__':
    main()
