#!/usr/bin/env python3
"""Explicit-device public PCM runner. No microphone, account or user-data operations."""
import argparse
import fcntl
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import tarfile

from verify_inputs import verify_inputs

PACKAGE = 'com.helix.agent.asrvalidation'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--models', nargs='+', required=True)
    parser.add_argument('--root', type=Path, default=Path('build/asr/benchmark'))
    parser.add_argument('--paced', action='store_true')
    parser.add_argument('--limit', type=int)
    parser.add_argument('--stage', action='store_true')
    parser.add_argument('--test-apk', type=Path)
    parser.add_argument('--moon-test-apk', type=Path)
    parser.add_argument('--run-label')
    args = parser.parse_args()
    device_root = args.root / args.serial
    device_root.mkdir(parents=True, exist_ok=True)
    device_lock = (device_root / 'runner.lock').open('w')
    try:
        fcntl.flock(device_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError as error:
        raise RuntimeError('Another benchmark owns this device') from error
    verify_inputs(args.root, args.models)
    destination = args.root / args.serial / (args.run_label or ('paced' if args.paced else 'throughput'))
    destination.mkdir(parents=True, exist_ok=True)

    def adb(*words, **kwargs):
        command = ['adb', '-s', args.serial, *words]
        return subprocess.run(command, check=True, **kwargs)

    if args.stage:
        archive = args.root / 'device-input.tar'
        with tarfile.open(archive, 'w') as target:
            for name in ['audio', 'corpus.json']:
                target.add(args.root / name, arcname=name)
            for model in args.models:
                target.add(args.root / 'models' / model, arcname='models/' + model)
        adb('shell', 'run-as', PACKAGE, 'mkdir', '-p', 'files/asr-benchmark')
        with archive.open('rb') as source:
            adb('shell', '-T', 'run-as', PACKAGE, 'sh', '-c',
                shlex.quote('cd files/asr-benchmark && tar -xf -'), stdin=source)
        manifest = {str(p.relative_to(args.root)): dict(size=p.stat().st_size,
                    sha256=hashlib.file_digest(p.open('rb'), 'sha256').hexdigest())
                    for name in ['audio'] + ['models/' + model for model in args.models]
                    for p in (args.root / name).rglob('*') if p.is_file()}
        manifest['corpus.json'] = dict(sha256=hashlib.file_digest((args.root / 'corpus.json').open('rb'), 'sha256').hexdigest())
        (destination / 'input-manifest.json').write_text(json.dumps(manifest, indent=2))
        checked = adb('exec-out', 'run-as', PACKAGE, 'sha256sum',
                      *['files/asr-benchmark/' + name for name in manifest], capture_output=True, text=True)
        actual = dict((line.split(maxsplit=1)[1].strip().removeprefix('files/asr-benchmark/'), line.split(maxsplit=1)[0]) for line in checked.stdout.splitlines())
        (destination / 'device-input-sha256.txt').write_text(checked.stdout)
        mismatches = [name for name, entry in manifest.items() if actual.get(name) != entry['sha256']]
        if mismatches:
            raise RuntimeError('Staged bytes differ: ' + ', '.join(mismatches))
    current_apk = None
    outcome_file = destination / 'outcomes.json'
    outcomes = json.loads(outcome_file.read_text()) if outcome_file.exists() else {}
    for model in args.models:
        apk = (args.moon_test_apk or args.root / 'moon-benchmark-test.apk') if model == 'moonshine' else (args.test_apk or args.root / 'benchmark-test.apk')
        if apk != current_apk:
            adb('install', '-r', '-t', str(apk))
            current_apk = apk
        command = ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                   'com.helix.app.voice.SpeechBenchmarkDeviceTest', '-e', 'model', model,
                   '-e', 'paced', str(args.paced).lower()]
        if args.limit is not None: command += ['-e', 'limit', str(args.limit)]
        command += [PACKAGE + '.test/com.helix.app.HelixAndroidJUnitRunner']
        with (destination / (model + '.log')).open('w') as output:
            process = adb(*command, stdout=output, stderr=subprocess.STDOUT)
        log = (destination / (model + '.log')).read_text()
        passed = 'OK (1 test)' in log and 'FAILURES!!!' not in log
        outcomes[model] = dict(passed=passed, testApkSha256=hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest())
        result = subprocess.run(['adb', '-s', args.serial, 'exec-out', 'run-as', PACKAGE,
                                'cat', 'files/asr-benchmark/results-' + model + '.jsonl'], capture_output=True)
        if result.returncode == 0:
            (destination / (model + '.jsonl')).write_bytes(result.stdout)
            rows = [json.loads(line) for line in result.stdout.decode().splitlines() if line]
            expected = json.loads((args.root / 'corpus.json').read_text())
            if args.limit is not None: expected = expected[:args.limit]
            valid = len(rows) == len(expected) and all(
                row['model'] == model and row['sample'] == item['id']
                and row['reference'] == item['reference']
                and abs(row['audioMs'] - item['samples'] / 16) < .001
                for row, item in zip(rows, expected))
            passed = passed and valid
        else:
            passed = False
        outcomes[model]['passed'] = passed
        outcomes[model]['receiptVerified'] = passed
        print(args.serial, model, 'PASS' if passed else 'FAIL', flush=True)
        (destination / 'outcomes.json').write_text(json.dumps(outcomes, indent=2))
    if not all(outcomes[model]['passed'] for model in args.models): raise SystemExit(1)


if __name__ == '__main__': main()
