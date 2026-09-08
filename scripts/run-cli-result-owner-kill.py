#!/usr/bin/env python3
"""Kill the result owner after fetch, durable persistence, or acknowledgement; never resubmit."""
import argparse
import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import time

from android_process_control import kill_emulator_app, verify_emulator_signal_control


def owned_path(job):
    assert re.fullmatch(r'job_[0-9a-f]{12}', job)
    return f'files/provider-v1/codex-model-jobs/{job}'


def age_owned_result(base, job, output):
    path = owned_path(job) + '/record.json'
    access = base + ['shell', 'run-as', 'com.helix.runtime.cli']
    record = json.loads(subprocess.check_output(access + ['cat', path], text=True))
    assert record['jobId'] == job and record['state'] == 'SUCCEEDED'
    assert 'reconciledAtEpochMillis' not in record
    (output / 'record-before-aging.json').write_text(json.dumps(record, indent=2))
    now = int(subprocess.check_output(base + ['shell', 'date', '+%s'], text=True)) * 1000
    record['terminalAtEpochMillis'] = now - 31 * 24 * 60 * 60 * 1000
    record['createdAtEpochMillis'] = record['terminalAtEpochMillis'] - 1
    subprocess.run(access + ['tee', path + '.expiry-fixture'], input=json.dumps(record),
                   text=True, capture_output=True, check=True)
    subprocess.run(access + ['mv', path + '.expiry-fixture', path], capture_output=True, check=True)
    (output / 'record-aged.json').write_text(json.dumps(record, indent=2))


def verify_expired_cleanup(base, job, output):
    path = owned_path(job)
    access = base + ['shell', 'run-as', 'com.helix.runtime.cli']
    record = json.loads(subprocess.check_output(access + ['cat', path + '/record.json'], text=True))
    assert record['jobId'] == job and record['state'] == 'EVIDENCE_EXPIRED'
    assert 'reconciledAtEpochMillis' not in record and 'outputSha256' not in record
    files = subprocess.check_output(access + ['ls', path], text=True).split()
    assert files == ['record.json'], files
    (output / 'record-expired.json').write_text(json.dumps(record, indent=2))
    subprocess.run(access + ['rm', '-r', path], capture_output=True, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--local-only', action='store_true')
    parser.add_argument('--expired', action='store_true')
    parser.add_argument('--boundary', choices=['fetched', 'persisted', 'acknowledged'], required=True)
    parser.add_argument('--adb', default=shutil.which('adb'))
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    if not args.adb:
        parser.error('adb required')
    if args.local_only and args.boundary != 'acknowledged':
        parser.error('--local-only requires --boundary acknowledged')
    if args.expired and (args.local_only or args.boundary == 'acknowledged'):
        parser.error('--expired requires unacknowledged online fixture')
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
    enabled_state = None
    if args.local_only:
        package = subprocess.check_output(base + ['shell', 'dumpsys', 'package', 'com.helix.runtime.cli'], text=True)
        state = re.search(r'User 0:.*enabled=(\d+)', package)
        assert state and state.group(1) in ('0', '1'), 'Runtime must start enabled'
        enabled_state = state.group(1)
    try:
        for phase in ['prepare', 'recover', 'recover-final']:
            if args.local_only and phase == 'recover':
                subprocess.run(base + ['shell', 'pm', 'disable-user', '--user', '0', 'com.helix.runtime.cli'], check=True, capture_output=True)
                disabled = subprocess.check_output(base + ['shell', 'dumpsys', 'package', 'com.helix.runtime.cli'], text=True)
                (args.output / 'runtime-disabled.txt').write_text(disabled)
                assert re.search(r'User 0:.*enabled=3', disabled)
            if args.expired and phase == 'recover':
                age_owned_result(base, job, args.output)
            log = args.output / f'cli-owner-{phase}.log'
            cmd = base + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                'com.helix.app.provider.CliResultOwnerKillDeviceTest', '-e', 'cli.result.phase', phase,
                '-e', 'cli.result.expired', str(args.expired).lower(),
                '-e', 'cli.result.boundary', args.boundary, '-e', 'cli.result.localOnly', str(args.local_only).lower(),
                'com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner']
            with log.open('w') as output:
                proc = subprocess.Popen(cmd, stdout=output, stderr=subprocess.STDOUT)
                if phase == 'prepare':
                    deadline = time.monotonic() + 15
                    match = None
                    while time.monotonic() < deadline:
                        match = re.search(r'CLI_RESULT_READY pid=(\d+) job=(job_[0-9a-f]{12})', log.read_text())
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
                    found = re.search(r'CLI_RESULT_RECOVERED job=(job_[0-9a-f]{12})', text)
                    assert found and found.group(1) == job
                    records.append(dict(phase=phase, job=job, tests=1))
    finally:
        if enabled_state is not None:
            action = 'default-state' if enabled_state == '0' else 'enable'
            subprocess.run(base + ['shell', 'pm', action, '--user', '0', 'com.helix.runtime.cli'], check=True, capture_output=True)
            restored = subprocess.check_output(base + ['shell', 'dumpsys', 'package', 'com.helix.runtime.cli'], text=True)
            (args.output / 'runtime-restored.txt').write_text(restored)
            assert re.search(r'User 0:.*enabled=' + enabled_state, restored)
    if args.expired:
        verify_expired_cleanup(base, job, args.output)
    result = dict(expired=args.expired, serial=args.serial, boundary=args.boundary, localOnly=args.local_only, restoredEnabledState=enabled_state, installedApks=hashes, records=records,
                  scope='Real Runtime result and production local result store; synthetic request and seeded call binding, not a full Goal run')
    (args.output / 'result.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result), flush=True)


if __name__ == '__main__':
    main()
