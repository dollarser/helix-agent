#!/usr/bin/env python3
"""Run one connected-test task and archive fresh XML before another run can replace it."""
import argparse
import hashlib
import fcntl
import tempfile
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time

SPEC = importlib.util.spec_from_file_location('summary', Path(__file__).with_name('summarize-android-tests.py'))
SUMMARY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SUMMARY)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--task', required=True, help='One :module:connectedVariantAndroidTest task')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--class', dest='test_class')
    parser.add_argument('--timeout', type=int, default=1800)
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial) or not re.fullmatch(r'(?::[\w-]+)+:connected\w*AndroidTest', args.task):
        parser.error('explicit emulator and connected AndroidTest task required')
    if not 1 <= args.timeout <= 86400:
        parser.error('timeout must be 1..86400 seconds')
    lock = Path(tempfile.gettempdir()) / ('helix-android-test-' + args.serial + '.lock')
    lease = lock.open('a')
    try:
        fcntl.flock(lease, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        parser.error('device already owned by another attempt runner')
    args.output.mkdir(parents=True, exist_ok=False)
    module = Path(*args.task.split(':')[1:-1])
    command = ['./gradlew', args.task, '--console=plain', '--max-workers=1']
    if args.test_class:
        command.append('-Pandroid.testInstrumentationRunnerArguments.class=' + args.test_class)
    result = {'state': 'RUNNING', 'serial': args.serial, 'task': args.task, 'command': command,
              'gitCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
              'sourceDiffSha256': hashlib.sha256(subprocess.check_output(['git', 'diff', 'HEAD'])).hexdigest()}
    def persist():
        temporary = args.output / 'result.tmp'
        temporary.write_text(json.dumps(result, indent=2))
        temporary.replace(args.output / 'result.json')
    persist()
    started = time.time_ns()
    try:
        with (args.output / 'gradle.log').open('w') as log:
            completed = subprocess.run(command, env={**os.environ, 'ANDROID_SERIAL': args.serial},
                                       stdout=log, stderr=subprocess.STDOUT, timeout=args.timeout)
        result['exitCode'] = completed.returncode
        paths = []
        for path in sorted((module / 'build/outputs/androidTest-results/connected').glob('**/TEST-*.xml')):
            if path.stat().st_mtime_ns < started:
                continue
            destination = args.output / 'xml' / path.relative_to(module / 'build/outputs/androidTest-results/connected')
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, destination)
            paths.append(destination)
        result['xmlSha256'] = {str(p.relative_to(args.output)): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
        result['summary'] = SUMMARY.summarize(paths)
        result['state'] = 'FAIL' if completed.returncode else result['summary']['status']
    except (OSError, ValueError, SUMMARY.ET.ParseError, subprocess.TimeoutExpired) as error:
        result.update(state='INVALID_EVIDENCE', error=str(error))
    except KeyboardInterrupt:
        result.update(state='CANCELLED')
    finally:
        result['endedUtcEpochNs'] = time.time_ns()
        persist()
    print(json.dumps(result))
    return 0 if result['state'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
