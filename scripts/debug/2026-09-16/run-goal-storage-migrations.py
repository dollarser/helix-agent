"""HXA-208: complete Room migration suite on fresh, exclusively owned API 29/36 emulators."""
import datetime
import json
import os
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[3]
SDK = Path(os.environ['ANDROID_HOME'])
ADB = str(SDK / 'platform-tools/adb')
OUT = ROOT / 'build' / ('goal-storage-' + datetime.datetime.now().strftime('%Y%m%d-%H%M%S'))
OUT.mkdir()
print(OUT.relative_to(ROOT), flush=True)

def run(args, **kwargs):
    return subprocess.run(args, cwd=ROOT, check=True, **kwargs)

def devices():
    return run([ADB, 'devices'], capture_output=True, text=True).stdout

rows = []
try:
    with (OUT / 'assemble.log').open('w') as log:
        run(['./gradlew', ':core:storage:assembleDebugAndroidTest', '--console=plain'], stdout=log, stderr=subprocess.STDOUT)
    apk = ROOT / 'core/storage/build/outputs/apk/androidTest/debug/storage-debug-androidTest.apk'
    for api, port in [(29, 5574), (36, 5576)]:
        serial = f'emulator-{port}'
        if serial in devices():
            raise RuntimeError('Refuse to borrow an existing emulator')
        with (OUT / f'emulator-{api}.log').open('w') as log:
            child = subprocess.Popen([str(SDK/'emulator/emulator'), '-avd', f'Helix_API_{api}', '-port', str(port),
                '-read-only', '-no-window', '-no-audio', '-no-snapshot', '-memory', '2048', '-cores', '2',
                '-gpu', 'swiftshader_indirect'], stdout=log, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 300
                while time.monotonic() < deadline:
                    if child.poll() is not None:
                        raise RuntimeError('Owned emulator exited')
                    boot = subprocess.run([ADB, '-s', serial, 'shell', 'getprop', 'sys.boot_completed'], capture_output=True, text=True)
                    if boot.stdout.strip() == '1':
                        break
                    time.sleep(2)
                else:
                    raise RuntimeError('Boot timeout')
                run([ADB, '-s', serial, 'install', '-r', str(apk)], capture_output=True)
                result = run([ADB, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                    'com.helix.core.storage.RoomMigrationFixtureTest',
                    'com.helix.core.storage.test/androidx.test.runner.AndroidJUnitRunner'], capture_output=True, text=True, timeout=600)
                raw = result.stdout + result.stderr
                (OUT / f'{api}-instrumentation.txt').write_text(raw)
                codes = [int(x) for x in re.findall(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)', raw)]
                row = dict(api=api, passed=codes.count(0), skipped=codes.count(-3)+codes.count(-4),
                    failed=sum(x not in [0,1,-3,-4] for x in codes))
                rows.append(row)
                print(json.dumps(row), flush=True)
                if row['failed'] or row['skipped'] or not row['passed'] or 'INSTRUMENTATION_CODE: -1' not in raw:
                    raise RuntimeError('Room device suite failed')
            finally:
                child.terminate()
                try:
                    child.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait(timeout=10)
                (OUT / f'owned-exit-{api}.txt').write_text(str(child.returncode))
finally:
    (OUT / 'summary.json').write_text(json.dumps(rows, indent=2))
    (OUT / 'adb-final.txt').write_text(devices())
