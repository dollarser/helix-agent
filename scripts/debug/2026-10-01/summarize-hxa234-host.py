"""Read current explicit XML reports; two App flavors are not separate unique scenarios."""
from pathlib import Path
import json
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[3]
reports = {
    'core:agent/test': 'core/agent/build/test-results/test',
    'tools:framework/test': 'tools/framework/build/test-results/test',
    'app/consumer': 'app/build/test-results/testConsumerDebugUnitTest',
    'app/developer': 'app/build/test-results/testDeveloperDebugUnitTest',
}
result = {}
for name, relative in reports.items():
    files = sorted((root / relative).glob('TEST-*.xml'))
    if not files:
        raise RuntimeError(f'Missing reports: {relative}')
    counts = {key: 0 for key in ('tests', 'failures', 'errors', 'skipped')}
    for path in files:
        suite = ET.parse(path).getroot()
        for key in counts:
            counts[key] += int(suite.get(key, '0'))
    result[name] = {'path': relative, 'suites': len(files), **counts}
result['newCoreSuites'] = {}
for name in ('TurnCommitPolicyTest', 'AgentLoopHeadlessTest', 'ContextCompilerTest'):
    path = root / f'core/agent/build/test-results/test/TEST-com.helix.core.agent.{name}.xml'
    suite = ET.parse(path).getroot()
    result['newCoreSuites'][name] = {k: int(suite.get(k, '0')) for k in ('tests', 'failures', 'errors', 'skipped')}
output = root / 'build/hxa234-host-summary.json'
output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
print(output.relative_to(root))
print(json.dumps(result, ensure_ascii=False, indent=2))
