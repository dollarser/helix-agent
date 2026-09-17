"""Owned physical-device P0 regression. Missing opt-in profiles remain explicit skips."""
import argparse
import fcntl
import tempfile
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('serial')
parser.add_argument('phase', choices=['focused', 'full', 'root-app'])
args = parser.parse_args()
root = Path(__file__).resolve().parents[4]
output = root / 'build' / ('p0-' + args.phase + '-' + datetime.now().strftime('%Y%m%d-%H%M%S'))
output.mkdir(parents=True)
adb = ['adb', '-s', args.serial]
assert subprocess.check_output(adb + ['get-state'], text=True).strip() == 'device'
assert not args.serial.startswith('emulator-'), 'physical acceptance requires physical device'
lock = (Path(tempfile.gettempdir()) / ('helix-p0-device-' + args.serial + '.lock')).open('w')
fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
previous_awake = subprocess.check_output(adb + ['shell', 'settings', 'get', 'global', 'stay_on_while_plugged_in'], text=True).strip()
try:
    # Foreground/UI baseline, not Doze or locked-screen qualification. Restore in finally.
    subprocess.run(adb + ['shell', 'svc', 'power', 'stayon', 'true'], check=True)
    subprocess.run(adb + ['shell', 'input', 'keyevent', 'KEYCODE_WAKEUP'], check=True)
    policy = subprocess.check_output(adb + ['shell', 'dumpsys', 'window', 'policy'], text=True)
    assert not re.search(r'^\s*(?:showing|mIsShowing)=true$', policy, re.M), 'Unlock the physical device before UI acceptance'
    apks = ['app/build/outputs/apk/developer/debug/app-developer-debug.apk',
            'app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk']
    manifest = {'head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
                'apks': {p: hashlib.sha256((root / p).read_bytes()).hexdigest() for p in apks}}
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2))
    for apk in apks:
        subprocess.run(adb + ['install', '-r', str(root / apk)], check=True)
    options = []
    if args.phase == 'focused':
        classes = ['com.helix.app.' + x for x in [
            'GoalDeletionDeviceTest', 'GoalReminderNavigationDeviceTest',
            'GoalReminderPublicationDeviceTest', 'GoalReminderTest',
            'capability.SystemCapabilityResolverTest', 'chat.AttachmentE2eDeviceTest']]
        options = ['-e', 'class', ','.join(classes)]
    elif args.phase == 'root-app':
        options = ['-e', 'class', 'com.helix.app.root.RootLifecycleDeviceTest#realAppDispatcherHonorsRootScopeAndToolDisable',
                   '-e', 'hxa094ExpectedRoot', 'granted']
    command = adb + ['shell', 'am', 'instrument', '-w', '-r', *options,
                     'com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner']
    (output / 'command.json').write_text(json.dumps(command, indent=2))
    print(output, flush=True)
    with (output / 'instrumentation.log').open('w') as log:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=7200)
    text = (output / 'instrumentation.log').read_text()
    codes = [int(v) for v in re.findall(r'^INSTRUMENTATION_STATUS_CODE: (-?\d+)$', text, re.M)]
    counts = {'passed': codes.count(0), 'skipped': codes.count(-3) + codes.count(-4),
              'failed': codes.count(-1) + codes.count(-2), 'returncode': result.returncode}
    counts['ok'] = bool(re.search(r'OK \([1-9]\d* tests?\)', text)) and not counts['failed'] and result.returncode == 0
    (output / 'result.json').write_text(json.dumps(counts, indent=2))
    print(counts, flush=True)
    raise SystemExit(0 if counts['ok'] else 1)
finally:
    if previous_awake == 'null':
        subprocess.run(adb + ['shell', 'settings', 'delete', 'global', 'stay_on_while_plugged_in'], check=False)
    else:
        subprocess.run(adb + ['shell', 'settings', 'put', 'global', 'stay_on_while_plugged_in', previous_awake], check=False)
    lock.close()
