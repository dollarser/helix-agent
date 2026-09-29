#!/usr/bin/env python3
"""Owner-authorized API36 tests; dedicated ephemeral emulator per flavor."""
import os
from pathlib import Path
import subprocess
import sys

classes = ['ui.ConversationComposerDeviceTest', 'ui.ModeLayoutDeviceTest',
           'ui.RuntimeSetupDeviceTest', 'ui.PickerRecoveryDeviceTest', 'chat.SessionRunControlDeviceTest',
           'voice.SpeechRecognitionDeviceTest', 'chat.ChatServiceAttachmentRetryDeviceTest#stalePickerSessionCannotStageIntoCurrentSession']
env = os.environ.copy()
env['ANDROID_HOME'] = str(Path.home() / 'Library/Android/sdk')
for index, flavor in enumerate(['developer']):
    package = 'com.helix.agent' + ('.developer' if flavor == 'developer' else '')
    suite = ['proot.ProotRepairLayoutDeviceTest']
    output = f'build/review-fixes-api36-r3-{flavor}'
    command = [sys.executable, 'scripts/run-owned-emulator.py', '--avd', 'Helix_HXA229_Closeout_API36',
               '--port', str(5560 + index * 2), '--memory-mb', '4096',
               '--apk', f'app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk',
               '--test-apk', f'app/build/outputs/apk/androidTest/{flavor}/debug/app-{flavor}-debug-androidTest.apk',
               '--runner', package + '.test/com.helix.app.HelixAndroidJUnitRunner',
               '--classes', ','.join('com.helix.app.' + cls for cls in suite),
               '--output', output, '--timeout', '900', '--raw-results', '--clear-app-data']
    if flavor == 'developer':
        command += ['--after-script', 'scripts/debug/2026-09-29/pull-slash-runtime-layout.py']
    with Path(output + '.log').open('w') as log:
        result = subprocess.run(command, env=env, stdout=log, stderr=subprocess.STDOUT)
    print(flavor, result.returncode, flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)
