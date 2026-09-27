"""Archive host counts and artifact identities without raw machine paths or device claims."""
import hashlib
import argparse
import json
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser()
parser.add_argument('--output', default='build/hxa210/verification-summary.json')
parser.add_argument('--device-status', choices=('not requested', 'pending', 'passed', 'failed'), default='not requested')
args = parser.parse_args()
modules = [('core/model', 'test'), ('core/agent', 'test'), ('core/workspace', 'test'),
           ('core/storage', 'testDebugUnitTest'), ('app', 'testConsumerDebugUnitTest'),
           ('app', 'testDeveloperDebugUnitTest')]
counts = {}
for module, variant in modules:
    totals = {key: 0 for key in ('tests', 'failures', 'errors', 'skipped')}
    for report in (ROOT / module / 'build/test-results' / variant).glob('TEST-*.xml'):
        suite = ET.parse(report).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, '0'))
    counts[f'{module}:{variant}'] = totals
artifacts = {}
for artifact in (ROOT / 'app/build/outputs/apk').rglob('*.apk'):
    artifacts[str(artifact.relative_to(ROOT))] = hashlib.sha256(artifact.read_bytes()).hexdigest()
output = {'gitCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
          'dirty': True, 'counts': counts, 'apkSha256': artifacts, 'device': args.device_status,
          'realProvider': 'not requested', 'schemaVersion': 1}
(ROOT / args.output).write_text(json.dumps(output, indent=2) + '\n')
print(json.dumps(counts, indent=2))
