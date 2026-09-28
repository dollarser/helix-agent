"""Archive raw Android status bundles as scoped HXA-227 evidence, including failed runs."""
import json
from pathlib import Path
import sys
import subprocess

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'scripts'))
from agent_eval import aggregate, artifact, fingerprint, markdown, normalize_junit, source_manifest, write_json
from instrumentation_junit import parse, to_xml

directory = Path(sys.argv[1]).resolve()
manifest = json.loads((ROOT / 'evals/trajectory/core-device.json').read_text())
raw = directory / 'instrumentation.txt'
records = parse(raw.read_text())
expected = {(ref['class'], ref['method']) for case in manifest['cases'] for ref in case['tests']}
assert set(records) == expected, 'Unexpected or missing instrumented tests'
xml = directory / 'results.xml'
xml.write_text(to_xml(records))
device = json.loads((directory / 'device-properties.json').read_text())
apks = json.loads((directory / 'artifacts.json').read_text())
source = json.loads((ROOT / 'build/hxa227/device-source-manifest.json').read_text())
current = source_manifest(ROOT, ['app/src', 'core', 'provider', 'tools', 'runtime', 'feature', 'gradle', 'buildSrc',
                                 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties'])
assert source['sha256'] == current['sha256'], 'Sources changed during device matrix'
write_json(directory / 'source-manifest.json', source)
verifiers = []
for name in sorted({key[0] for key in expected}):
    path = ROOT / 'app/src/androidTest/kotlin' / (name.replace('.', '/') + '.kt')
    verifiers.append({'class': name, 'source': path.read_text()})
flavor = directory.name.split('-')[0]
context = {'gitCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
           'dirty': True, 'sourceManifestSha': source['sha256'], 'verifierSha': fingerprint(verifiers),
           'fixtureSha': fingerprint(manifest), 'environmentSha': fingerprint({k:v for k,v in device.items() if k != 'serial'}),
           'flavor': flavor, 'api': int(device['api']), 'device': device['avd'],
           'provider': 'controlled-fixtures', 'model': 'not-applicable', 'protocol': 'mixed-fixture',
           'providerVersion': 'not-applicable', 'appApkSha': apks['app'], 'testApkSha': apks['test']}
write_json(directory / 'context.json', context)
envelopes = normalize_junit(manifest, [xml], context, directory)
for record in envelopes:
    record['cost']['wallMillis'] = None  # Raw Android bundles have no per-case timing.
    record['outcome']['artifacts'] += [artifact(raw, directory), artifact(directory / 'artifacts.json', directory),
                                      artifact(directory / 'device-properties.json', directory)]
    if record['outcome']['verifiedResult'] == 'FAIL':
        record['outcome']['failureCategory'] = None  # Attribution needs investigation, not an exception string.
write_json(directory / 'envelopes.json', envelopes)
report = aggregate(envelopes)
write_json(directory / 'summary.json', report)
(directory / 'summary.md').write_text(markdown(report))
print([(r['identity']['caseId'], r['outcome']['verifiedResult']) for r in envelopes])
