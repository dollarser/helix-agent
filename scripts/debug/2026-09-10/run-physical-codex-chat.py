"""Run only the opt-in synthetic Codex chat smoke, preserving personal app data and credentials."""
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
parser.add_argument('--model')
parser.add_argument('--effort', default='OFF')
parser.add_argument('--probe-only', action='store_true')
parser.add_argument('--runtime-foreground', action='store_true')
parser.add_argument('--mode', choices=['CHAT', 'PLAN', 'ACT'], default='CHAT')
args = parser.parse_args()
assert not args.serial.startswith('emulator-')
adb = Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb'
def device(*command, timeout=180):
    try:
        result = subprocess.run([str(adb), '-s', args.serial, *command], capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired as failure:
        if args.output.exists():
            partial = failure.stdout or b''
            (args.output / 'instrumentation-timeout.txt').write_bytes(
                partial.encode() if isinstance(partial, str) else partial)
        raise
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout + result.stderr

assert device('shell', 'getprop', 'ro.kernel.qemu').strip() != '1'
package = 'com.helix.agent.developer'
test_package = package + '.test'
installed = device('shell', 'pm', 'list', 'packages', 'com.helix').splitlines()
assert 'package:' + package in installed
assert 'package:' + test_package not in installed
args.output.mkdir(parents=True, exist_ok=False)
source = Path('app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk')
apk = args.output / source.name
shutil.copy2(source, apk)
manifest = {'testSha256': hashlib.sha256(apk.read_bytes()).hexdigest()}
owned = False
try:
    installed = device('install', '-r', str(apk))
    assert 'Success' in installed, installed
    owned = True
    raw = device('shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                 'com.helix.app.provider.CodexSubscriptionProviderRealAccountDeviceTest',
                 '-e', 'realSubscription', 'true', '-e', 'realProvider', 'codex',
                 '-e', 'realEffort', args.effort, '-e', 'realMode', args.mode,
                 '-e', 'probeOnly', str(args.probe_only).lower(),
                 '-e', 'runtimeForeground', str(args.runtime_foreground).lower(),
                 *(['-e', 'realModel', args.model] if args.model else []),
                 test_package + '/com.helix.app.HelixAndroidJUnitRunner')
    (args.output / 'instrumentation.txt').write_text(raw)
    print(raw, flush=True)
    manifest['passed'] = bool(re.search(r'^OK \(1 test\)', raw, re.M)) and not any(
        marker in raw for marker in ['FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed'])
    assert manifest['passed'], 'Real app chat smoke failed'
finally:
    if owned:
        manifest['cleanup'] = device('uninstall', test_package).strip()
    (args.output / 'result.json').write_text(json.dumps(manifest, indent=2))
