"""Bounded owner-authorized diagnostic; dirty source, not an official P5 baseline."""
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
serial, destination = sys.argv[1:]
out = Path(destination)
adb = str(Path.home() / 'Library/Android/sdk/platform-tools/adb')
records = []

def command(args, path, timeout=360):
    with path.open('w') as log:
        try:
            result = subprocess.run(args, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
            return result.returncode
        except subprocess.TimeoutExpired:
            log.write('\nDIAGNOSTIC HOST TIMEOUT\n')
            return 124

def instrument(flavor, classes, round_id, extra=()):
    package = 'com.helix.agent' + ('.developer' if flavor == 'developer' else '')
    subprocess.run([adb, '-s', serial, 'shell', 'pm', 'clear', package], check=True)
    log = out / f'{flavor}-{round_id}.txt'
    code = command([adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-e', 'class', classes,
                    *extra, package + '.test/com.helix.app.HelixAndroidJUnitRunner'], log)
    data = log.read_text()
    passed = code == 0 and 'OK (' in data and 'FAILURES!!!' not in data
    records.append({'run': str(log.name), 'exit': code, 'passed': passed})
    (out / 'bounded-summary.json').write_text(json.dumps(records, indent=2))
    if not passed:
        command([adb, '-s', serial, 'logcat', '-d'], out / f'{flavor}-{round_id}-logcat.txt', 30)
        subprocess.run([adb, '-s', serial, 'shell', 'am', 'force-stop', package], check=True)

saf = 'com.helix.app.ui.FilesImportExportUiTest'
for i in ():
    instrument('consumer', saf, f'saf-{i}')
for apk in ('app/build/outputs/apk/developer/debug/app-developer-debug.apk',
            'app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk'):
    subprocess.run([adb, '-s', serial, 'install', '-r', apk], check=True)
instrument('developer', saf, 'saf-final')
for i in range(1, 4):
    target = out / f'model-round-{i}'
    target.mkdir()
    env = os.environ.copy()
    env['HELIX_P5_CASES'] = 'goal-001'
    with (out / f'model-round-{i}.log').open('w') as log:
        result = subprocess.run([sys.executable, 'scripts/debug/2026-09-28/run-p5-sglang-suites.py', serial, str(target)],
                                cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=700)
    records.append({'run': f'model-round-{i}', 'exit': result.returncode, 'passed': result.returncode == 0})
    (out / 'bounded-summary.json').write_text(json.dumps(records, indent=2))
if not all(row['passed'] for row in records):
    raise SystemExit(1)
