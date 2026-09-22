#!/usr/bin/env python3
"""HXA-215 latest-message revision acceptance. HXA-214 full UI acceptance remains separate."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from owned_acceptance import collect_owned, test_records
spec = importlib.util.spec_from_file_location('matrix', ROOT / 'scripts/debug/2026-09-21/run-acceptance-matrix.py')
matrix = importlib.util.module_from_spec(spec)
spec.loader.exec_module(matrix)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scope', choices=['215'], required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--api', type=int, choices=[29,36], action='append')
    parser.add_argument('--flavor', choices=['consumer','developer'], action='append')
    parser.add_argument('--only', choices=['regression','recovery'], default=None)
    parser.add_argument('--first-port', type=int, default=5700)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    classes = ['com.helix.app.chat.MessageRevisionDeviceTest', 'com.helix.app.ui.MessageEditResendFlowDeviceTest',
        'com.helix.app.chat.ChatServiceAttachmentRetryDeviceTest', 'com.helix.app.chat.ConversationDraftRecoveryDeviceTest',
        'com.helix.app.chat.SessionForkDeviceTest', 'com.helix.app.ui.SessionForkFlowDeviceTest',
        'com.helix.app.chat.ContextHistoryDeviceTest', 'com.helix.app.chat.ContextCompactionDeviceTest',
        'com.helix.app.chat.LongTurnCompactionDeviceTest', 'com.helix.app.ui.ChatCompactionFlowDeviceTest',
        'com.helix.app.ui.ChatStopProgressDeviceTest', 'com.helix.app.chat.GoalRunCoordinatorDeviceTest',
        'com.helix.app.chat.GoalUsageReservationsDeviceTest', 'com.helix.app.chat.GoalTurnBindingDeviceTest']
    outcomes = []
    port = args.first_port
    for flavor in args.flavor or ['consumer','developer']:
        for api in args.api or [29,36]:
            for phase in ([args.only] if args.only else ['regression','recovery']):
                label = f'{flavor}-api{api}-{phase}'
                selected = classes if phase == 'regression' else ['com.helix.app.ui.MessageEditRecoveryDeviceTest#seedRevisionRecovery']
                expected, sources = matrix.methods_for(selected, flavor)
                (args.output / (label+'-expected.json')).write_text(json.dumps({'methods':expected,'sources':sources},indent=2))
                target = args.output / label
                suffix = '.developer' if flavor == 'developer' else ''
                command = [sys.executable, 'scripts/debug/2026-09-18/run-owned-emulator-207.py',
                    '--avd', f'Helix191_API{api}', '--port', str(port), '--memory-mb','4096','--cores','4',
                    '--apk',f'app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk',
                    '--test-apk',f'app/build/outputs/apk/androidTest/{flavor}/debug/app-{flavor}-debug-androidTest.apk',
                    '--runner',f'com.helix.agent{suffix}.test/com.helix.app.HelixAndroidJUnitRunner',
                    '--classes',','.join(selected),'--output',str(target),'--timeout','900']
                if phase == 'recovery': command += ['--after-script','scripts/debug/2026-09-22/revision-process-after.py']
                print('Starting '+label, flush=True)
                with (args.output / (label+'.log')).open('w') as log:
                    result = subprocess.run(command,cwd=ROOT,env=dict(os.environ,HXA215_PACKAGE='com.helix.agent'+suffix),stdout=log,stderr=subprocess.STDOUT)
                report = collect_owned(target,expected) if result.returncode == 0 else {'exit':result.returncode}
                if phase == 'recovery' and result.returncode == 0:
                    normal = json.loads((target / 'normal-process.json').read_text())
                    records = test_records((target/'verify-logcat.txt').read_text())
                    verified = records.get('com.helix.app.ui.MessageEditRecoveryDeviceTest#verifyRevisionRecovery',{})
                    assert normal['beforePid'] != normal['afterPid'] and verified.get('status') == 'passed'
                    report['normalProcess'] = normal
                    report['verifyMethods'] = records
                (args.output / (label+'-report.json')).write_text(json.dumps(report,indent=2))
                outcomes.append({'batch':label,'exit':result.returncode,'verdict':report.get('verdict'),'counts':report.get('counts')})
                (args.output/'batches.json').write_text(json.dumps(outcomes,indent=2))
                print(outcomes[-1],flush=True)
                if result.returncode or report.get('verdict') != 'DEVICE_BATCH_PASS': return 1
                port += 2
    return 0

if __name__ == '__main__': raise SystemExit(main())
