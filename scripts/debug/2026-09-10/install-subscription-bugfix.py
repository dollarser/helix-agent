"""Cover-install the subscription bugfix pair on one explicit phone; never clear account data."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', required=True)
p.add_argument('--output', required=True, type=Path)
p.add_argument('--allow-runtime-install', action='store_true', help='Allow first installation on an explicitly selected new phone')
a = p.parse_args()
assert not a.serial.startswith('emulator-')
adb = Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb'
def device(*args):
    return subprocess.check_output([str(adb), '-s', a.serial, *args], text=True, timeout=120)

assert device('get-state').strip() == 'device'
assert device('shell', 'getprop', 'ro.kernel.qemu').strip() != '1'
a.output.mkdir(parents=True, exist_ok=False)
records = []
for package, apk, activity in (
    ('com.helix.runtime.cli', 'runtime/cli-app/build/outputs/apk/debug/cli-app-debug.apk',
     'com.helix.runtime.cli.app.CliRuntimeHomeActivity'),
    ('com.helix.agent.developer', 'app/build/outputs/apk/developer/debug/app-developer-debug.apk',
     'com.helix.app.MainActivity'),
):
    installed = ('package:' + package) in device('shell', 'pm', 'list', 'packages', package).splitlines()
    assert installed or (package == 'com.helix.runtime.cli' and a.allow_runtime_install)
    result = device('install', '-r', apk)
    assert 'Success' in result, result
    launch = device('shell', 'am', 'start', '-W', '-n', package + '/' + activity)
    assert 'Status: ok' in launch, launch
    records.append({'package': package, 'apk': apk, 'previouslyInstalled': installed,
                    'sha256': hashlib.sha256(Path(apk).read_bytes()).hexdigest(), 'install': result})
    (a.output / 'install.json').write_text(json.dumps(records, indent=2))
    print(package + (': updated; data preserved' if installed else ': installed; login required'), flush=True)
