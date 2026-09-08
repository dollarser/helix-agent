#!/usr/bin/env python3
"""Bounded, opt-in emulator pressure; preserve raw failures and restore only this fixture."""
import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import time
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--compiler', required=True, help='NDK aarch64-linux-android29-clang')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--max-mib', type=int, default=1280, choices=range(16, 1537))
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('This fixture is restricted to an explicitly selected emulator')
    out = args.output
    out.mkdir(parents=True, exist_ok=False)
    base = [args.adb, '-s', args.serial]

    def adb(*command, check=True):
        return subprocess.run(base + list(command), capture_output=True, text=True, check=check, timeout=30)

    assert adb('shell', 'getprop', 'ro.kernel.qemu').stdout.strip() == '1'
    run_id = uuid.uuid4().hex[:12]
    remote = '/data/local/tmp/helix-pressure-' + run_id
    cache = 'cache/resource-pressure-' + run_id + '.csv'
    released = 'cache/resource-pressure-' + run_id + '.released'
    package = 'com.helix.agent.developer'
    binary = out / 'resource-pressure'
    compiler = [args.compiler, '-O2', '-Wall', '-Wextra', '-Werror',
                str(Path(__file__).parent / 'fixtures/resource-pressure.c'), '-o', str(binary)]
    subprocess.run(compiler, check=True, capture_output=True)
    apks = []
    for pkg, apk in [(package, Path('app/build/outputs/apk/developer/debug/app-developer-debug.apk')),
                     (package + '.test', Path('app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk'))]:
        adb('install', '-r', '-t', str(apk))
        installed = adb('shell', 'pm', 'path', pkg).stdout.strip().removeprefix('package:')
        sha = hashlib.sha256(apk.read_bytes()).hexdigest()
        assert adb('shell', 'sha256sum', installed).stdout.split()[0] == sha
        apks.append({'package': pkg, 'path': str(apk), 'sha256': sha})
    adb('push', str(binary), remote)
    adb('shell', 'chmod', '700', remote)
    command = base + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                     'com.helix.app.runcontrol.ResourcePressureDeviceTest', '-e', 'resource.pressure.runId', run_id,
                     package + '.test/com.helix.app.HelixAndroidJUnitRunner']
    result = {'serial': args.serial, 'apks': apks, 'command': command, 'compiler': compiler,
              'maxMiB': args.max_mib, 'allocationSecondsLimit': 45, 'runId': run_id}
    pressure = None
    native_pid = None
    samples = ''

    def stop_pressure():
        if native_pid:
            live = adb('shell', 'cat', f'/proc/{native_pid}/cmdline', check=False)
            if remote in live.stdout:
                adb('shell', 'kill', '-TERM', str(native_pid), check=False)

    with (out / 'instrumentation.log').open('w') as log, (out / 'allocation.log').open('w') as allocation:
        instrumentation = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 100
            while time.monotonic() < deadline and instrumentation.poll() is None:
                response = adb('shell', 'run-as', package, 'cat', cache, check=False)
                if response.returncode == 0:
                    samples = response.stdout
                    if pressure is None and len(samples.splitlines()) >= 2:
                        pressure = subprocess.Popen(base + ['shell', remote, str(args.max_mib), '45'],
                                                    stdout=allocation, stderr=subprocess.STDOUT)
                    if pressure is not None:
                        first = (out / 'allocation.log').read_text().splitlines()
                        if first and re.fullmatch(r'PID \d+', first[0]):
                            native_pid = int(first[0].split()[1])
                        if any(row['lowMemory'] == 'true' and row['allowance'] == '1'
                               for row in csv.DictReader(io.StringIO(samples))):
                            stop_pressure()
                        if pressure.poll() is not None:
                            adb('shell', 'run-as', package, 'touch', released)
                time.sleep(0.5)
            result['instrumentationExit'] = instrumentation.wait(timeout=10)
        finally:
            stop_pressure()
            if pressure is not None:
                result['allocatorExit'] = pressure.wait(timeout=15)
            if instrumentation.poll() is None:
                instrumentation.terminate()
                instrumentation.wait(timeout=10)
            response = adb('shell', 'run-as', package, 'cat', cache, check=False)
            if response.returncode == 0:
                samples = response.stdout
            (out / 'memory.csv').write_text(samples)
            adb('shell', 'rm', '-f', remote, check=False)
            adb('shell', 'run-as', package, 'rm', '-f', cache, released, check=False)
            text = (out / 'instrumentation.log').read_text()
            codes = re.findall(r'^INSTRUMENTATION_STATUS_CODE: (-?\d+)$', text, re.M)
            result['passed'] = result.get('instrumentationExit') == 0 and codes == ['1', '0'] and 'OK (1 test)' in text
            result['statusCodes'] = codes
            (out / 'result.json').write_text(json.dumps(result, indent=2))
    print(json.dumps({'serial': args.serial, 'passed': result['passed']}), flush=True)
    if not result['passed']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
