#!/usr/bin/env python3
"""Owner-authorized HXA-232 bounded local-fixture validation; never attaches to a borrowed device."""
import os
from pathlib import Path
import subprocess
import sys
flavor = sys.argv[1]
run = sys.argv[2]
assert flavor in ('consumer', 'developer')
env = os.environ.copy()
env['ANDROID_HOME'] = str(Path.home() / 'Library/Android/sdk')
package = 'com.helix.agent' + ('.developer' if flavor == 'developer' else '')
classes = ['chat.NativeJavascriptDeviceTest', 'chat.AutomaticRecoveryPersistenceDeviceTest',
           'chat.UserQuestionPersistenceDeviceTest', 'chat.GoalContinuationDeviceTest',
           'chat.GoalInputSchedulingDeviceTest', 'chat.ChatSubmissionReceiptDeviceTest',
           'chat.SessionInputApprovalDeviceTest', 'ApprovalFlowDeviceTest', 'ui.ApprovalLayoutDeviceTest',
           'ui.DisclosureDialogTest', 'ui.DisclosureReadableUiTest', 'ui.ConversationReceiptRaceDeviceTest']
if len(sys.argv) > 3:
 classes = [sys.argv[3]]
output = f'build/hxa232-api36-{run}-{flavor}'
command = [sys.executable, 'scripts/run-owned-emulator.py', '--avd', 'Helix_HXA229_Closeout_API36',
 '--port', os.environ.get('HXA232_EMULATOR_PORT', '5560'), '--memory-mb', '4096', '--apk', f'app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk',
 '--test-apk', f'app/build/outputs/apk/androidTest/{flavor}/debug/app-{flavor}-debug-androidTest.apk',
 '--runner', package + '.test/com.helix.app.HelixAndroidJUnitRunner',
 '--classes', ','.join('com.helix.app.' + c for c in classes), '--output', output,
 '--timeout', '1200', '--raw-results', '--clear-app-data']
with Path(output + '.log').open('w') as log:
 result = subprocess.run(command, env=env, stdout=log, stderr=subprocess.STDOUT)
print(output, result.returncode, flush=True)
raise SystemExit(result.returncode)
