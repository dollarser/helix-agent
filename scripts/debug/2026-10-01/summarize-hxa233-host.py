#!/usr/bin/env python3
"""Read current, explicit Gradle reports and built APK identities; never executes devices."""
from pathlib import Path
from hashlib import sha256
import json
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[3]
targets = {
    'cli-client': 'runtime/cli-client/build/test-results/testDebugUnitTest',
    'cli-app': 'runtime/cli-app/build/test-results/testDebugUnitTest',
    'storage': 'core/storage/build/test-results/testDebugUnitTest',
    'consumer': 'app/build/test-results/testConsumerDebugUnitTest',
    'developer': 'app/build/test-results/testDeveloperDebugUnitTest',
}
new_suites = {
    'CliReplayMaintenanceTest', 'CliReplayCallsTest', 'AntigravityReplayMaintenanceTest',
    'ReplayQuiescenceTest', 'SubscriptionReplayRetentionTest',
}
report = {'head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
          'dirty': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=root)),
          'device': 'not executed', 'modules': {}, 'new_suites': {}, 'artifacts': {}}
for name, relative in targets.items():
    paths = sorted((root / relative).glob('TEST-*.xml'))
    if not paths:
        raise RuntimeError(f'Missing test reports for {name}')
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for path in paths:
        suite = ET.parse(path).getroot()
        values = {key: int(suite.attrib.get(key, '0')) for key in totals}
        for key, value in values.items():
            totals[key] += value
        short = suite.attrib.get('name', '').rsplit('.', 1)[-1]
        if short in new_suites:
            report['new_suites'][short] = values
    report['modules'][name] = totals
for relative in (
    'app/build/outputs/apk/consumer/debug/app-consumer-debug.apk',
    'app/build/outputs/apk/developer/debug/app-developer-debug.apk',
    'app/build/outputs/apk/androidTest/consumer/debug/app-consumer-debug-androidTest.apk',
    'app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk',
):
    path = root / relative
    with path.open('rb') as stream:
        digest = sha256()
        while data := stream.read(1024 * 1024):
            digest.update(data)
    report['artifacts'][relative] = {'bytes': path.stat().st_size, 'sha256': digest.hexdigest()}
output = root / 'build/hxa233-host-summary.json'
output.write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
if new_suites != report['new_suites'].keys():
    raise RuntimeError('Missing expected regression suite')
if any(v['failures'] or v['errors'] for v in report['modules'].values()):
    raise RuntimeError('Recorded test failures remain')
