"""Owned-runner follow-up: real window widths, bounded dialog tests, screenshot collection."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

serial, destination = sys.argv[1:]
output = Path(destination)
owner = json.loads((output / 'owner.json').read_text())
assert owner['serial'] == serial and owner['avd'].startswith('Helix_HXA210_API')
os.kill(owner['pid'], 0)
adb = str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb')

def command(*args, binary=False):
    for attempt in range(40):
        os.kill(owner['pid'], 0)
        state = subprocess.run([adb, '-s', serial, 'get-state'], capture_output=True, text=True, timeout=10)
        if state.returncode == 0 and state.stdout.strip() == 'device':
            break
        time.sleep(0.5)
    else:
        raise TimeoutError('Owned emulator transport remained unavailable')
    return subprocess.check_output([adb, '-s', serial, *args], text=not binary, timeout=240)

inventory = command('shell', 'pm', 'list', 'instrumentation')
runners = re.findall(r'instrumentation:(com\.helix\.agent(?:\.developer)?\.test/com\.helix\.app\.HelixAndroidJUnitRunner)', inventory)
assert len(runners) == 1, inventory
runner = runners[0]
package = runner.split('/')[0].removesuffix('.test')
results = []
try:
    for width in (320, 360, 412):
        command('shell', 'wm', 'size', f'{width * 5 // 2}x2400')
        command('shell', 'wm', 'density', '400')
        result = command('shell', 'am', 'instrument', '-w', '-e', 'class',
                         'com.helix.app.ui.WorkspaceCleanupDialogDeviceTest',
                         '-e', 'workspaceWidth', str(width), runner)
        (output / f'cleanup-{width}.txt').write_text(result)
        assert 'OK (6 tests)' in result and 'FAILURES!!!' not in result, result
        results.append({'widthDp': width, 'fontScales': [1, 2],
                        'languageModes': ['ZH_CN', 'EN', 'SYSTEM'], 'tests': 6, 'result': 'passed'})
finally:
    command('shell', 'wm', 'size', '1080x2400')
archive = command('exec-out', 'run-as', package, 'tar', '-C', 'files', '-cf', '-', 'hxa210-ui-evidence', binary=True)
(output / 'cleanup-screenshots.tar').write_bytes(archive)
(output / 'cleanup-widths.json').write_text(json.dumps(results, indent=2) + '\n')
print(json.dumps(results, indent=2))
