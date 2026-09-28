"""Audit a closed owned run against raw per-method statuses and archived identities."""
from collections import Counter
import hashlib
import json
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(root / 'scripts'))
from instrumentation_junit import parse, to_xml
from agent_eval import source_manifest, write_json

output = Path(sys.argv[1])
assert json.loads((output / 'closed.json').read_text())['exit'] == 0
artifacts = json.loads((output / 'artifacts.json').read_text())
for key in ('app', 'test'):
    assert hashlib.sha256((output / (key + '.apk')).read_bytes()).hexdigest() == artifacts[key]
manifest = json.loads((output / 'current-consumer-manifest.json').read_text())
assert len(manifest['classes']) == len(set(manifest['classes']))
rows = [json.loads(path.read_text()) for path in sorted((output / 'baseline/results').glob('*.json'))]
assert len(rows) == len(manifest['classes'])
assert set(row['class'] for row in rows) == set(manifest['classes'])
methods, class_counts = Counter(), Counter()
records = {}
for row in rows:
    class_counts[row['verdict']] += 1
    if row['verdict'] == 'PHASE_RUNNER_REQUIRED':
        continue
    parsed = parse(Path(row['log_path']).read_text())
    assert all(identity[0] == row['class'] for identity in parsed)
    assert not records.keys() & parsed.keys()
    codes = [value[0] for value in parsed.values()]
    if row['verdict'] == 'PASS':
        assert 0 in codes and not set(codes) & {-1, -2}
    elif row['verdict'] == 'SKIP / ASSUMPTION':
        assert set(codes) <= {-3, -4}
    for code in codes:
        methods['passed' if code == 0 else 'skipped' if code in (-3, -4) else 'failed'] += 1
    records.update(parsed)
archived_source = json.loads((output / 'device-source-manifest.json').read_text())
current = source_manifest(root, ['app/src', 'core', 'provider', 'tools', 'runtime', 'feature', 'gradle',
                                'buildSrc', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties'])
assert archived_source == current, 'Source changed during the owned run'
(output / 'baseline-methods.xml').write_text(to_xml(records))
report = {'classes': len(rows), 'classVerdicts': dict(class_counts), 'methods': dict(methods),
          'artifacts': artifacts, 'sourceUnchanged': True, 'ownedEmulatorClosed': True}
write_json(output / 'audited-summary.json', report)
print(json.dumps(report, indent=2))
