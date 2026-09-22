"""Exercise all install commit boundaries on the exclusively owned runner device."""
import json
import os
from pathlib import Path
import subprocess
import sys

serial, output = sys.argv[1], Path(sys.argv[2])
owner = json.loads((output / 'owner.json').read_text())
if owner['serial'] != serial:
    raise SystemExit('Device ownership mismatch')
os.kill(owner['pid'], 0)
adb = str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb')
runner = os.environ['HXA129_RUNNER']
cls = 'com.helix.app.connector.ConnectorInstallRecoveryDeviceTest'
for boundary in ['preparing', 'before-commit', 'after-commit']:
    def instrument(method):
        os.kill(owner['pid'], 0)
        result = subprocess.run([adb, '-s', serial, 'shell', 'am', 'instrument', '-w',
                                 '-e', 'class', cls + '#' + method, '-e', 'recoveryPhase', boundary,
                                 runner], capture_output=True, text=True, timeout=90, check=True).stdout
        (output / f'recovery-{boundary}-{method}.txt').write_text(result)
        return result
    result = instrument('prepare')
    if 'process crashed' not in result.lower():
        raise RuntimeError(f'{boundary}: no process death: {result}')
    result = instrument('verify')
    if 'OK (1 test)' not in result or 'FAILURES' in result:
        raise RuntimeError(f'{boundary}: verification failed: {result}')
    print(f'PASS recovery {boundary}', flush=True)
