#!/usr/bin/env python3
"""Owned-runner follow-up: replace APKs without clearing data, verify new-code phase."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from owned_acceptance import split_recovery_log, test_records

serial, output_arg = sys.argv[1:]
output = Path(output_arg).resolve()
owner = json.loads((output / 'owner.json').read_text())
assert owner['serial'] == serial and not (output / 'closed.json').exists()
os.kill(owner['pid'], 0)
adb = [str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb'), '-s', serial]
package = 'com.helix.agent.developer'

def device(*args):
    os.kill(owner['pid'], 0)
    if (output / 'closed.json').exists():
        raise RuntimeError('owned process already closed')
    return subprocess.check_output([*adb, *args], text=True, stderr=subprocess.STDOUT, timeout=240)

def digest(path):
    return hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()

def installed(label):
    paths = device('shell', 'pm', 'path', package).strip().splitlines()
    assert len(paths) == 1 and paths[0].startswith('package:')
    target = output / f'{label}-installed.apk'
    device('pull', paths[0][len('package:'):], str(target))
    (output / f'{label}-package.txt').write_text(device('shell', 'dumpsys', 'package', package))
    return digest(target)

old_hash = installed('old')
assert old_hash == digest(output / 'app.apk'), 'old APK identity differs'
old_pid = int(device('shell', 'run-as', package, 'cat', 'no_backup/apk-upgrade-evidence/pid').strip())
(output / 'upgrade-seed-pid.txt').write_text(str(old_pid))
new_app = output / 'new-app.apk'
new_test = output / 'new-test.apk'
shutil.copyfile(os.environ['HELIX_UPGRADE_NEW_APK'], new_app)
shutil.copyfile(os.environ['HELIX_UPGRADE_NEW_TEST_APK'], new_test)
assert digest(new_app) != old_hash, 'requires distinct old/new production APKs'
# Android install -r checks the signing identity; neither uninstall nor pm clear is used.
(output / 'replace-app.txt').write_text(device('install', '-r', str(new_app)))
(output / 'replace-test.txt').write_text(device('install', '-r', str(new_test)))
new_hash = installed('new')
assert new_hash == digest(new_app), 'installed new APK identity differs'
device('logcat', '-b', 'all', '-c')
result = device('shell', 'am', 'instrument', '-w', '-e', 'class',
    'com.helix.app.proot.ApkReplacementUpgradeDeviceTest', '-e', 'upgradePhase', 'verify',
    package + '.test/com.helix.app.HelixAndroidJUnitRunner')
(output / 'upgrade-verification.txt').write_text(result)
raw = device('logcat', '-d', '-s', 'TestRunner')
(output / 'upgrade-verification-all-phases.txt').write_text(raw)
raw, prior = split_recovery_log(raw, old_pid)
(output / 'upgrade-verification-logcat.txt').write_text(raw)
(output / 'upgrade-seed-tail.txt').write_text(prior)
(output / 'upgrade-log-phases.json').write_text(json.dumps({
    'seedPid': old_pid, 'identitySource': 'upgrade-seed-pid.txt',
    'allPhasesSha256': digest(output / 'upgrade-verification-all-phases.txt'),
    'verificationSha256': digest(output / 'upgrade-verification-logcat.txt'),
}, indent=2))
records = test_records(raw)
assert set(records) == {'com.helix.app.proot.ApkReplacementUpgradeDeviceTest#dataAndRuntimeEvidenceSurviveApkReplacement'}
assert all(row['status'] == 'passed' for row in records.values()), 'Upgrade method did not pass'
if not re.search(r'^OK \(1 test\)', result, re.M) or any(x in result for x in ['FAILURES!!!', 'Process crashed', 'INSTRUMENTATION_FAILED']):
    raise RuntimeError(result)
(output / 'upgrade-result.json').write_text(json.dumps({
    'oldApkSha256': old_hash, 'newApkSha256': new_hash,
    'newTestApkSha256': digest(new_test), 'command': 'adb install -r',
    'clearedData': False, 'result': 'PASS',
}, indent=2))
print(result)
