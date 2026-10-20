#!/usr/bin/env python3
"""Collect only synthetic account/voice acceptance reports from an owned test instance."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from instrumentation_junit import require_passed, to_xml

serial, destination = sys.argv[1:]
root = Path(destination)
sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT',
                                                       str(Path.home() / 'Library/Android/sdk'))))
adb = str(sdk / 'platform-tools/adb')
package = subprocess.check_output([str(sdk / 'cmdline-tools/latest/bin/apkanalyzer'),
                                   'manifest', 'application-id', str(root / 'app.apk')], text=True).strip()
if package not in {'com.helix.agent', 'com.helix.agent.developer'}:
    raise ValueError('Evidence must use the frozen integrated app identity')
expected = json.loads((root / 'artifacts.json').read_text())
installed = {}
for label, target in (('app', package), ('test', package + '.test')):
    paths = subprocess.check_output([adb, '-s', serial, 'shell', 'pm', 'path', target], text=True).splitlines()
    if len(paths) != 1 or not paths[0].startswith('package:/data/app/'):
        raise ValueError('Missing unique installed APK identity')
    data = subprocess.check_output([adb, '-s', serial, 'exec-out', 'cat', paths[0][len('package:'):]])
    installed[label] = hashlib.sha256(data).hexdigest()
    if installed[label] != expected[label]:
        raise ValueError('Installed APK differs from the frozen successful candidate')
(root / 'installed-artifacts.json').write_text(json.dumps(installed, indent=2))
result = subprocess.check_output([adb, '-s', serial, 'exec-out', 'run-as', package,
                                  'cat', 'cache/account-service-device-result.txt'])
if not result.startswith(b'login=passed\n') or b'boundRequestRejected=passed\n' not in result:
    raise RuntimeError('Missing complete account acceptance report')
(root / 'account-service-result.txt').write_bytes(result)
for name in ('cloud-speech-device-result.txt', 'account-login-form.png'):
    data = subprocess.check_output([adb, '-s', serial, 'exec-out', 'run-as', package,
                                   'cat', 'cache/' + name])
    if (name.endswith('.png') and data.startswith(b'\x89PNG')) or data.startswith(b'input='):
        (root / name).write_bytes(data)
    else:
        raise RuntimeError('Invalid synthetic evidence payload')
setting = subprocess.check_output([adb, '-s', serial, 'shell', 'settings', 'get', 'secure',
                                   'show_ime_with_hard_keyboard'], text=True).strip()
(root / 'keyboard-setting.json').write_text(json.dumps({'showImeWithHardKeyboard': setting}))
if setting != '1':
    raise RuntimeError('Owned emulator keyboard preference not applied')
# Bounded UI/capture checks use injected PCM and synthetic engines, never the microphone.
classes = ','.join(('com.helix.app.voice.VoiceCaptureDeviceTest',
                    'com.helix.app.ui.ReadAloudUiDeviceTest',
                    'com.helix.app.ui.OfflineVoiceDialogDeviceTest'))
run = subprocess.run([adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r',
                      '-e', 'class', classes,
                      package + '.test/com.helix.app.HelixAndroidJUnitRunner'],
                     capture_output=True, text=True, timeout=180)
(root / 'voice-boundaries-instrumentation.txt').write_text(run.stdout + run.stderr)
run.check_returncode()
records = require_passed(run.stdout)
(root / 'voice-boundaries-junit.xml').write_text(to_xml(records))
print(f'Synthetic voice/cancellation boundaries: {len(records)} passed, no skips')
