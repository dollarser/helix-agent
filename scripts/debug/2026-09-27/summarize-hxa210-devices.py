"""Summarize only completed owned-emulator runs; retain failed iterations separately."""
import json
from pathlib import Path

root = Path(__file__).resolve().parents[3]
base = root / 'build/hxa210'
runs = {f'{flavor}-api{api}-r6': 20 if flavor == 'consumer' else 21
        for flavor in ('consumer', 'developer') for api in (29, 36)}
runs.update({f'developer-job-api{api}-r3': 3 for api in (29, 36)})
records = []
for name, count in runs.items():
    directory = base / 'device' / name
    result = (directory / 'instrumentation.txt').read_text()
    closed = json.loads((directory / 'closed.json').read_text())
    assert closed['exit'] == 0, (name, closed)
    assert f'OK ({count} tests)' in result and 'FAILURES!!!' not in result, name
    records.append({'run': name, 'tests': count, 'result': 'passed',
                    'device': json.loads((directory / 'device-properties.json').read_text()),
                    'apkSha256': json.loads((directory / 'artifacts.json').read_text())})
context = json.loads((base / 'host-candidate-emulator-final/context.json').read_text())
for flavor in ('consumer', 'developer'):
    identities = {json.dumps(r['apkSha256'], sort_keys=True) for r in records if r['run'].startswith(flavor)}
    assert len(identities) == 1, (flavor, 'APK identities differ within final matrix')
summary = {'scope': 'bounded Workspace emulator matrix and Job checks',
           'sourceManifestSha': context['sourceManifestSha'],
           'tests': sum(r['tests'] for r in records), 'runs': records,
           'physicalDevice': 'not requested', 'realProvider': 'not requested',
           'remainingAcceptance': ['system picker and persisted grant revocation',
                                   'cleanup confirmation screenshots',
                                   'additional process interruption cutpoints',
                                   'old artifact UI navigation']}
(base / 'device-verification-final.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary, indent=2))
