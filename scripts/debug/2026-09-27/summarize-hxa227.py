"""Archive compact host counts and artifact identities; never promotes device compilation to PASS."""
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path.cwd()
locations = {
    'model': 'core/model/build/test-results/test',
    'storage': 'core/storage/build/test-results/testDebugUnitTest',
    'agent': 'core/agent/build/test-results/test',
    'consumer': 'app/build/test-results/testConsumerDebugUnitTest',
    'developer': 'app/build/test-results/testDeveloperDebugUnitTest',
}
counts = {}
for label, folder in locations.items():
    totals = dict.fromkeys(('tests', 'failures', 'errors', 'skipped'), 0)
    for file in sorted((root / folder).glob('TEST-*.xml')):
        attributes = ET.parse(file).getroot().attrib
        for key in totals:
            totals[key] += int(attributes.get(key, 0))
    counts[label] = totals
apks = {}
for pattern in ('app/build/outputs/apk/consumer/debug/*.apk',
                'app/build/outputs/apk/developer/debug/*.apk',
                'app/build/outputs/apk/androidTest/consumer/debug/*.apk',
                'app/build/outputs/apk/androidTest/developer/debug/*.apk'):
    for path in root.glob(pattern):
        apks[path.relative_to(root).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
baseline = root / 'build/hxa227/host-baseline-final'
value = {'hostCounts': counts, 'apkSha256': apks,
         'baseline': json.loads((baseline / 'summary.json').read_text()),
         'context': json.loads((baseline / 'context.json').read_text()),
         'device': 'not requested', 'realProvider': 'not requested'}
destination = root / 'build/hxa227/verification-summary.json'
destination.write_text(json.dumps(value, indent=2) + '\n')
print(json.dumps({'hostCounts': counts, 'apkSha256': apks}, indent=2))
