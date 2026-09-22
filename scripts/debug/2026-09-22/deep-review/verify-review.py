"""Verify production preservation and summarize only this review's test tasks."""
from pathlib import Path
import argparse
import hashlib
import json
import xml.etree.ElementTree as ET

root = Path.cwd()
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, default=Path('build/deep-review'))
args = parser.parse_args()
out = args.output.resolve()
if not out.is_relative_to((root / 'build').resolve()):
    raise SystemExit('Output must be inside ignored build/')
before = json.loads((out / 'production-before.json').read_text())
changed = [p for p, sha in before.items()
           if not (root / p).exists() or hashlib.sha256((root / p).read_bytes()).hexdigest() != sha]
results = {}
for module, task in [('core/agent', 'test'), ('tools/framework', 'test'),
                     ('core/storage', 'testDebugUnitTest'), ('provider/openai-chat', 'test')]:
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    probes = []
    for path in (root / module / 'build/test-results' / task).glob('TEST-*.xml'):
        suite = ET.parse(path).getroot()
        for key in counts:
            counts[key] += int(suite.get(key, 0))
        if 'ReviewProbe' in path.name:
            if path.stat().st_mtime_ns < (out / 'production-before.json').stat().st_mtime_ns:
                raise SystemExit(f'Stale probe XML: {path}')
            probes.append(dict(name=suite.get('name'), output=suite.findtext('system-out'),
                               tests=suite.get('tests'), failures=suite.get('failures'),
                               errors=suite.get('errors', '0'), skipped=suite.get('skipped', '0')))
            target = out / 'probe-results' / path.name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(path.read_bytes())
    results[module] = dict(counts=counts, probes=probes)
report = dict(baseline_files=len(before), production_changes=changed, results=results)
(out / 'verification.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
inputs = json.loads((out / 'probe-inputs.json').read_text())
if any(not (root / p).is_file() or hashlib.sha256((root / p).read_bytes()).hexdigest() != sha
       for p, sha in inputs.items()):
    raise SystemExit('Probe inputs changed during the run')
if changed:
    raise SystemExit('Production baseline changed; inspect before claiming preservation')
expected = {'core/agent': 1, 'tools/framework': 2, 'core/storage': 1, 'provider/openai-chat': 1}
for module, count in expected.items():
    actual = sum(int(p['tests']) for p in results[module]['probes'])
    if actual != count or any(int(p[k]) for p in results[module]['probes'] for k in ('failures', 'errors', 'skipped')):
        raise SystemExit(f'{module}: expected {count} passing review probes, found {actual}; inspect fresh task log')
