#!/usr/bin/env python3
"""Verify Goal review/commit SIGKILL boundaries on one explicitly selected emulator.

Requires the matching developer app and test APK already installed, and emulator su.
Only the dedicated test fixture database is reset. No Runtime credentials are touched.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time

PACKAGE = 'com.helix.agent.developer'
RUNNER = PACKAGE + '.test/com.helix.app.HelixAndroidJUnitRunner'
TEST = 'com.helix.app.goal.GoalEvidenceProcessKillDeviceTest'


def exercise(prefix, output, boundary):
    base = prefix + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', TEST,
                     '-e', 'goal.evidence.boundary', boundary]
    log = output / (boundary + '-prepare.log')
    with log.open('w') as stream:
        process = subprocess.Popen(base + ['-e', 'goal.evidence.phase', 'prepare', RUNNER],
                                   stdout=stream, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 60
            pid = None
            while time.monotonic() < deadline:
                match = re.search(r'GOAL_EVIDENCE_KILL_READY boundary=' + boundary + r' pid=(\d+)',
                                  log.read_text())
                if match:
                    pid = match.group(1)
                    break
                if process.poll() is not None:
                    raise RuntimeError('Preparation failed: ' + str(log))
                time.sleep(0.2)
            if pid is None:
                raise RuntimeError('No kill-ready signal: ' + str(log))
            live = subprocess.check_output(prefix + ['shell', 'pidof', PACKAGE], text=True, timeout=10).split()
            if pid not in live:
                raise RuntimeError('Prepared PID is not the current app process')
            killed = subprocess.run(prefix + ['shell', 'su', '0', 'kill', '-9', pid],
                                    capture_output=True, text=True, timeout=10)
            (output / (boundary + '-kill.log')).write_text(killed.stdout + killed.stderr)
            killed.check_returncode()
            process.wait(timeout=30)
            live = subprocess.run(prefix + ['shell', 'pidof', PACKAGE], capture_output=True,
                                  text=True, timeout=10)
            if pid in live.stdout.split():
                raise RuntimeError('Killed PID is still alive')
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=10)
    recovered = subprocess.run(base + ['-e', 'goal.evidence.phase', 'recover', RUNNER],
                               capture_output=True, text=True, timeout=90)
    text = recovered.stdout + recovered.stderr
    (output / (boundary + '-recover.log')).write_text(text)
    if recovered.returncode != 0 or 'OK (1 test)' not in text or 'FAILURES' in text:
        raise RuntimeError('Recovery assertions failed: ' + boundary)
    return {'boundary': boundary, 'killedPid': pid, 'passed': True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('serial')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--boundary', choices=['staged', 'uncommitted', 'committed', 'review-uncommitted', 'reading'])
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('Select an emulator explicitly; physical devices are excluded')
    args.output.mkdir(parents=True, exist_ok=False)
    sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk')))
    prefix = [str(sdk / 'platform-tools/adb'), '-s', args.serial]
    rows = []
    for boundary in ([args.boundary] if args.boundary else ['staged', 'uncommitted', 'committed', 'review-uncommitted', 'reading']):
        rows.append(exercise(prefix, args.output, boundary))
        (args.output / 'result.json').write_text(json.dumps({'serial': args.serial, 'cases': rows}, indent=2) + '\n')
        print(args.serial + ' ' + boundary + ' passed', flush=True)


if __name__ == '__main__':
    main()
