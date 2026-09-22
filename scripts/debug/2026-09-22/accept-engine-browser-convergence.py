"""Run bounded integration regressions on four exclusively owned emulator batches."""
import argparse
import os
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, required=True)
output = parser.parse_args().output.resolve()
if not output.is_relative_to((root / 'build').resolve()):
    raise SystemExit('Output must be inside build/')
output.mkdir(parents=True, exist_ok=False)
classes = ','.join([
    'com.helix.app.chat.ToolSettlementRecoveryDeviceTest',
    'com.helix.app.BrowserActivityLifecycleDeviceTest',
    'com.helix.app.ui.BrowserRedesignUiDeviceTest',
    'com.helix.app.chat.GoalUsageReservationsDeviceTest',
    'com.helix.app.chat.ParkedTurnCancelSettlementDeviceTest',
    'com.helix.app.chat.ConversationStopConsistencyDeviceTest',
])
for index, (api, flavor) in enumerate([(29, 'consumer'), (29, 'developer'), (36, 'consumer'), (36, 'developer')]):
    suffix = '' if flavor == 'consumer' else '.developer'
    command = [sys.executable, str(root / 'scripts/run-owned-emulator.py'),
               '--avd', f'Helix191_API{api}', '--port', str(5640 + index * 2),
               '--apk', f'app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk',
               '--test-apk', f'app/build/outputs/apk/androidTest/{flavor}/debug/app-{flavor}-debug-androidTest.apk',
               '--classes', classes, '--runner', f'com.helix.agent{suffix}.test/com.helix.app.HelixAndroidJUnitRunner',
               '--output', str(output / f'api{api}-{flavor}'), '--timeout', '600']
    with (output / f'api{api}-{flavor}.log').open('w') as log:
        subprocess.run(command, cwd=root, env=os.environ, stdout=log, stderr=subprocess.STDOUT, check=True)
    print(f'PASS api{api}-{flavor}', flush=True)
