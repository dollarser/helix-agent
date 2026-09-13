"""Update an explicitly selected physical phone without uninstalling or clearing data."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

p = argparse.ArgumentParser()
p.add_argument('--serial', required=True)
p.add_argument('--output', required=True, type=Path)
a = p.parse_args()
assert not a.serial.startswith('emulator-')
a.output.mkdir(parents=True, exist_ok=False)
adb = str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb')
def device(*args):
    return subprocess.check_output([adb, '-s', a.serial, *args], text=True, timeout=120)
assert device('shell', 'getprop', 'ro.kernel.qemu').strip() != '1'
pkg = 'com.helix.agent.developer'
assert 'package:' + pkg in device('shell', 'pm', 'list', 'packages', pkg).splitlines()
(a.output / 'package-before.log').write_text(device('shell', 'dumpsys', 'package', pkg))
apk = Path('app/build/outputs/apk/developer/debug/app-developer-debug.apk')
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
installed = device('install', '-r', str(apk))
assert 'Success' in installed, installed
launch = device('shell', 'am', 'start', '-W', '-n', pkg + '/com.helix.app.MainActivity')
assert 'Status: ok' in launch, launch
(a.output / 'package-after.log').write_text(device('shell', 'dumpsys', 'package', pkg))
(a.output / 'result.json').write_text(json.dumps({'sha256': digest, 'install': installed, 'launch': launch}, indent=2))
print(installed + launch)
