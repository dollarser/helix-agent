"""Verify permission navigation and system-bar layout in a fresh consumer sandbox on USB phone."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--output', required=True, type=Path)
args = parser.parse_args()
assert not args.serial.startswith('emulator-')
adb = Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb'
def device(*command, timeout=180):
    result = subprocess.run([str(adb), '-s', args.serial, *command], capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout + result.stderr

assert device('shell', 'getprop', 'ro.kernel.qemu').strip() != '1'
installed = device('shell', 'pm', 'list', 'packages', 'com.helix').splitlines()
artifacts = {
    'com.helix.agent': 'app/build/outputs/apk/consumer/debug/app-consumer-debug.apk',
    'com.helix.agent.test': 'app/build/outputs/apk/androidTest/consumer/debug/app-consumer-debug-androidTest.apk',
}
assert all('package:' + package not in installed for package in artifacts)
args.output.mkdir(parents=True, exist_ok=False)
classes = ['SystemPermissionsNavigationDeviceTest', 'SystemBarInsetsDeviceTest', 'ConversationTopBarDeviceTest']
result = {'artifacts': {}, 'classes': classes, 'cleanup': []}
owned = []
try:
    for package, path in artifacts.items():
        apk = args.output / (package + '.apk')
        shutil.copy2(path, apk)
        result['artifacts'][package] = hashlib.sha256(apk.read_bytes()).hexdigest()
        assert 'Success' in device('install', str(apk))
        owned.append(package)
    raw = device('shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                 ','.join('com.helix.app.ui.' + name for name in classes),
                 'com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner', timeout=600)
    (args.output / 'instrumentation.txt').write_text(raw)
    result['passed'] = len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: 0\s*$', raw, re.M))
    result['success'] = bool(re.search(r'^OK \([1-9][0-9]* tests?\)', raw, re.M)) and not any(
        marker in raw for marker in ['FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed'])
    assert result['success'], 'Device UI regression failed; see instrumentation.txt'
finally:
    for package in reversed(owned):
        result['cleanup'].append({package: device('uninstall', package).strip()})
    (args.output / 'result.json').write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2), flush=True)
