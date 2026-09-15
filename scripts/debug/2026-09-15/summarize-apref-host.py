"""Summarize the exact host XML suites used for the HXA-200 correction."""
from pathlib import Path
import json
import xml.etree.ElementTree as ET

groups = {
    'model': 'core/model/build/test-results/test/TEST-*.xml',
    'policy': 'core/policy/build/test-results/test/TEST-*.xml',
    'agent': 'core/agent/build/test-results/test/TEST-*.xml',
    'framework': 'tools/framework/build/test-results/test/TEST-*.xml',
    'storage': 'core/storage/build/test-results/testDebugUnitTest/TEST-*.xml',
    'consumer': 'app/build/test-results/testConsumerDebugUnitTest/TEST-*.xml',
    'developer': 'app/build/test-results/testDeveloperDebugUnitTest/TEST-*.xml',
}
summary = {}
for group, glob in groups.items():
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for file in Path('.').glob(glob):
        suite = ET.parse(file).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, '0'))
    summary[group] = totals
Path('build/hxa200-fix-host-summary.json').write_text(json.dumps(summary, indent=2))
print(json.dumps(summary, indent=2))
