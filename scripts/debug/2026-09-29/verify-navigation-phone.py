"""Owner-authorized, bounded UI checks; no reset, accounts, or real model calls."""
from pathlib import Path
import subprocess
import os
import sys

ADB = str(Path(os.environ.get('ANDROID_HOME', Path.home() / 'Library/Android/sdk')) / 'platform-tools/adb')
SERIAL = sys.argv[1]
PACKAGE = 'com.helix.agent.developer'
OUT = Path('build/interaction-phone')
OUT.mkdir(parents=True, exist_ok=True)

def adb(*args, check=True, timeout=180):
    r = subprocess.run([ADB, '-s', SERIAL, *args], capture_output=True, timeout=timeout)
    if check and r.returncode:
        raise RuntimeError(r.stderr.decode(errors='replace') + r.stdout.decode(errors='replace'))
    return r

# The standard runner pins UI language. Preserve this one non-sensitive preference.
paths = ['shared_prefs/com.helix.app.language.xml', 'shared_prefs/com.helix.app.language.xml.bak']
original = {}
for path in paths:
    r = adb('exec-out', 'run-as', PACKAGE, 'cat', path, check=False)
    text = r.stdout
    original[path] = None if b'No such file' in text or r.returncode else text
    if original[path] is not None:
        (OUT / Path(path).name).write_bytes(text)
try:
    for apk in ['app/build/outputs/apk/developer/debug/app-developer-debug.apk',
                'app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk']:
        print(adb('install', '-r', apk).stdout.decode(), flush=True)
    adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    classes = ','.join('com.helix.app.ui.' + cls for cls in [
        'HierarchicalNavigationDeviceTest', 'ProviderContextRecoveryDeviceTest', 'ProviderSettingsFormDeviceTest'])
    result = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', classes,
                 PACKAGE + '.test/com.helix.app.HelixAndroidJUnitRunner', check=False, timeout=240)
    output = result.stdout.decode(errors='replace') + result.stderr.decode(errors='replace')
    (OUT / 'instrumentation.txt').write_text(output)
    print(output, flush=True)
    if result.returncode or 'FAILURES!!!' in output or 'OK (8 tests)' not in output:
        raise RuntimeError('Targeted instrumentation did not report 8 passing tests')
finally:
    adb('shell', 'am', 'force-stop', PACKAGE, check=False)
    for path, content in original.items():
        if content is None:
            adb('shell', 'run-as', PACKAGE, 'rm', '-f', path)
        else:
            # Only fixed, trusted preference paths are interpolated into the shell command.
            subprocess.run([ADB, '-s', SERIAL, 'shell', 'run-as', PACKAGE,
                            'sh', '-c', "'cat > " + path + "'"], input=content, check=True)
    print(adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/com.helix.app.MainActivity').stdout.decode(), flush=True)
