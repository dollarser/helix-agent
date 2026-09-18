"""Fresh merged-source journeys, serial owned emulators; never reuse/delete evidence."""
from pathlib import Path
import argparse
import json
import subprocess
import sys

root = Path(__file__).resolve().parents[3]
runner = root / 'scripts/debug/2026-09-09/run-owned-emulator.py'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--start-index', type=int, choices=range(10), default=0)
args = parser.parse_args()
index = 0


def run_case(base, output, extra):
    global index
    current = index
    index += 1
    if current < args.start_index:
        evidence = root / output
        assert 'OK (' in (evidence / 'instrumentation.txt').read_text()
        assert json.loads((evidence / 'closed.json').read_text())['exit'] == 0
        return
    print(f'RUN {output}', flush=True)
    # Each fresh process gets its own port, avoiding ADB's delayed serial removal.
    subprocess.run(base + ['--port', str(5650 + 2 * current), '--output', output] + extra,
                   cwd=root, check=True)


for api in (29, 36):
    for flavor in ('consumer', 'developer'):
        package = 'com.helix.agent' + ('.developer' if flavor == 'developer' else '')
        base = [sys.executable, str(runner), '--avd', f'HelixApkUpgrade_API{api}_20260918',
                '--memory-mb', '4096', '--cores', '4',
                '--apk', f'app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk',
                '--test-apk', f'app/build/outputs/apk/androidTest/{flavor}/debug/app-{flavor}-debug-androidTest.apk',
                '--runner', f'{package}.test/com.helix.app.HelixAndroidJUnitRunner', '--timeout', '600']
        for phase, setup in [('recovery', 'com.helix.app.RecoveryJourneyDeviceTest'),
                             ('readiness', 'com.helix.app.ui.CapabilityReadinessDeviceTest')]:
            classes = setup
            if phase == 'recovery':
                classes += ',com.helix.app.ui.ChatStopProgressDeviceTest,com.helix.app.chat.ToolResultReadDeviceTest'
            extra = ['--airplane-mode'] if phase == 'readiness' else []
            output = f'build/merge-193-195-batch-b/{flavor}-api{api}-{phase}'
            run_case(base, output, ['--classes', classes, '--recovery-setup-class', setup,
                                   '--instrument-arg', 'recoveryPhase=verify'] + extra)
        if flavor == 'developer':
            output = f'build/merge-193-195-batch-b/{flavor}-api{api}-logs-runtime'
            run_case(base, output, ['--classes', 'com.helix.app.proot.ProotLogStreamDeviceTest,'
                                   'com.helix.app.proot.CapabilityReadinessRuntimeDeviceTest'])
