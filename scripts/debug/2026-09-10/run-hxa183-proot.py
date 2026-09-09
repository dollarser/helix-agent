#!/usr/bin/env python3
"""PRoot lifecycle plus both sides' boundary suites, inside an owned emulator's lifetime."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

serial, directory = sys.argv[1:]
output = Path(directory)
owner = json.loads((output / 'owner.json').read_text())
assert owner['serial'] == serial
os.kill(owner['pid'], 0)
adb = str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb')
artifacts = {}
for path in [
    'app/build/outputs/apk/developer/debug/app-developer-debug.apk',
    'app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk',
    'runtime/proot-app/build/outputs/apk/debug/proot-app-debug.apk',
    'runtime/proot-app/build/outputs/apk/androidTest/debug/proot-app-debug-androidTest.apk',
]:
    artifacts[path] = hashlib.sha256(Path(path).read_bytes()).hexdigest()
(output / 'proot-artifacts.json').write_text(json.dumps(artifacts, indent=2))
with (output/'lifecycle.txt').open('w') as log:
    subprocess.run(['bash', 'scripts/accept-hxa-086-lifecycle.sh', serial], stdout=log,
                   stderr=subprocess.STDOUT, check=True, timeout=1000)
assert f'HXA-086 lifecycle acceptance PASSED on {serial}' in (output/'lifecycle.txt').read_text()
for path, digest in artifacts.items():
    assert hashlib.sha256(Path(path).read_bytes()).hexdigest() == digest, 'Build artifact changed during acceptance'

def suite(name, runner, classes):
    os.kill(owner['pid'], 0)
    result = subprocess.run([adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r',
                             '-e', 'class', classes, runner], text=True, capture_output=True,
                            check=True, timeout=600)
    text = result.stdout
    (output/(name+'.txt')).write_text(text+result.stderr)
    assert re.search(r'^OK \([1-9][0-9]* tests?\)', text, re.M), name
    assert not any(x in text for x in ['FAILURES!!!','INSTRUMENTATION_FAILED','Process crashed','INSTRUMENTATION_STATUS_CODE: -3']), name
    print(name, re.search(r'^OK .*', text, re.M).group(), flush=True)

suite('companion', 'com.helix.runtime.proot.test/androidx.test.runner.AndroidJUnitRunner',
      ','.join('com.helix.runtime.proot.app.'+name for name in [
          'ProotJobRunnerDeviceTest','ProotOutputDeliveryDeviceTest','ProotAcknowledgementDeviceTest',
          'ProotResultArchiveDeviceTest','ProotJobOwnersDeviceTest']))
suite('main-proot', 'com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner',
      ','.join('com.helix.app.proot.'+name for name in [
          'LinuxRunToolE2eDeviceTest','ProotJobE2eDeviceTest','ProotResultStoreDeviceTest','ProotResultUiDeviceTest']))
