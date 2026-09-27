"""Real process deaths over explicitly seeded cleanup cutpoints in a fixture-only DB."""
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

def command(*args):
    # Wait on the same owned process after a transient transport loss; never replay instrumentation.
    for attempt in range(40):
        os.kill(owner['pid'], 0)
        state = subprocess.run([adb, '-s', serial, 'get-state'], capture_output=True, text=True, timeout=10)
        if state.returncode == 0 and state.stdout.strip() == 'device':
            break
        time.sleep(0.5)
    else:
        raise TimeoutError('Owned emulator transport remained unavailable')
    return subprocess.check_output([adb, '-s', serial, *args], text=True, timeout=180)

inventory = command('shell', 'pm', 'list', 'instrumentation')
runners = re.findall(r'instrumentation:(com\.helix\.agent(?:\.developer)?\.test/com\.helix\.app\.HelixAndroidJUnitRunner)', inventory)
assert len(runners) == 1, inventory
runner = runners[0]
records = []
cuts = [('com.helix.app.chat.WorkspaceProcessRecoveryDeviceTest', cut)
        for cut in ('setup-before-rename', 'setup-purging', 'setup-purged')]
cuts += [('com.helix.app.files.WorkspaceBackupRecoveryDeviceTest', cut)
         for cut in ('setup-prepared', 'setup-deleted', 'setup-restoring')]
for fixture, cut in cuts:
    setup = command('shell', 'am', 'instrument', '-w', '-e', 'class', fixture, '-e', 'recoveryPhase', cut, runner)
    (output / f'{cut}.txt').write_text(setup)
    assert 'process crashed' in setup.lower(), setup
    verify = command('shell', 'am', 'instrument', '-w', '-e', 'class', fixture, runner)
    (output / f'{cut}-verify.txt').write_text(verify)
    assert 'OK (1 test)' in verify and 'FAILURES!!!' not in verify, verify
    records.append({'fixture': fixture, 'cut': cut,
                    'setup': 'actual backup boundary process death' if 'Backup' in fixture else 'seeded cleanup state then actual process death',
                    'verify': 'passed'})
(output / 'cleanup-recovery-cuts.json').write_text(json.dumps(records, indent=2) + '\n')
print(json.dumps(records, indent=2))
