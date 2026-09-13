"""Read-only SHA verification of our latest developer/CLI builds against the explicit phone."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--output', required=True, type=Path)
args = parser.parse_args()
assert not args.serial.startswith('emulator-')
adb = Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb'
def device(*command):
    return subprocess.check_output([str(adb), '-s', args.serial, *command], text=True, timeout=30)
result = {}
for package, apk in {
    'com.helix.agent.developer': 'app/build/outputs/apk/developer/debug/app-developer-debug.apk',
    'com.helix.runtime.cli': 'runtime/cli-app/build/outputs/apk/debug/cli-app-debug.apk',
}.items():
    paths = device('shell', 'pm', 'path', package).splitlines()
    assert len(paths) == 1 and paths[0].startswith('package:/data/app/')
    remote = paths[0].removeprefix('package:')
    # A package-manager APK path has no shell metacharacters; pass through adb's argument list.
    assert all(character.isalnum() or character in '/._~+=-' for character in remote)
    expected = hashlib.sha256(Path(apk).read_bytes()).hexdigest()
    actual = device('shell', 'sha256sum', remote).split()[0]
    result[package] = {'sha256': actual, 'matchesBuild': actual == expected}
    assert actual == expected, package
result['launchers'] = device('shell', 'cmd', 'package', 'query-activities', '--brief',
                            '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER',
                            '-p', 'com.helix.runtime.cli')
assert '1 activities found:' in result['launchers']
result['packages'] = device('shell', 'pm', 'list', 'packages', 'helix').splitlines()
args.output.write_text(json.dumps(result, indent=2))
print(json.dumps(result, indent=2))
