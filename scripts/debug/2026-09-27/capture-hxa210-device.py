"""Capture identity of a live task-owned emulator without touching any other device."""
import json
import os
from pathlib import Path
import subprocess
import sys

output = Path(sys.argv[1])
owner = json.loads((output / 'owner.json').read_text())
os.kill(owner['pid'], 0)
adb = str(Path.home() / 'Library/Android/sdk/platform-tools/adb')

def command(*args):
    os.kill(owner['pid'], 0)
    return subprocess.check_output([adb, '-s', owner['serial'], *args], text=True, timeout=10).strip()

assert owner['avd'] in command('emu', 'avd', 'name').splitlines()
if '--progress' in sys.argv[2:]:
    print(command('logcat', '-d', '-s', 'TestRunner', 'AndroidRuntime', 'libc')[-5000:])
    sys.exit(0)
if '--failures' in sys.argv[2:]:
    lines = command('logcat', '-d', '-s', 'TestRunner').splitlines()
    print('\n'.join(line for line in lines if ' E TestRunner:' in line)[:7000])
    sys.exit(0)
record = {key: command('shell', 'getprop', prop) for key, prop in {
    'api': 'ro.build.version.sdk', 'abi': 'ro.product.cpu.abi',
    'fingerprint': 'ro.build.fingerprint', 'securityPatch': 'ro.build.version.security_patch',
}.items()}
record.update(serial=owner['serial'], avd=owner['avd'],
              size=command('shell', 'wm', 'size'), density=command('shell', 'wm', 'density'))
(output / 'device-properties.json').write_text(json.dumps(record, indent=2) + '\n')
print(json.dumps(record, indent=2))
