#!/usr/bin/env python3
"""Summarize existing build evidence; never runs a model, Runtime or Android device."""
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

out = Path('build/hxa239')
before = json.loads((out / 'source-before.json').read_text())
after = json.loads((out / 'source-after.json').read_text())
assert before['sourceManifestSha'] == after['sourceManifestSha'], 'Source changed during validation'

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

reports = {}
for flavor in ('consumer', 'developer'):
    folder = Path(f'app/build/test-results/test{flavor.title()}DebugUnitTest')
    for name in ('com.helix.app.ui.ComposerAvailabilityTest', 'com.helix.app.ui.composer.ComposerTextValueTest'):
        path = folder / f'TEST-{name}.xml'
        suite = ET.parse(path).getroot()
        counts = {key: int(suite.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')}
        assert counts['tests'] > 0 and not any(counts[k] for k in ('failures', 'errors', 'skipped'))
        reports[str(path)] = dict(counts, sha256=digest(path), timestamp=suite.get('timestamp'))

resources = {}
for language in ('values', 'values-en', 'values-zh-rCN'):
    path = Path(f'app/src/main/res/{language}/composer-feedback.xml')
    entries = ET.parse(path).getroot().findall('string')
    values = {item.attrib['name']: ''.join(item.itertext()) for item in entries}
    assert len(values) == len(entries) == 7 and all(values.values())
    resources[language] = values
base = resources['values']
assert base == resources['values-zh-rCN']
for values in resources.values():
    assert set(values) == set(base)
    for name, text in values.items():
        assert re.findall(r'%\d*\$?[sdf]', text) == re.findall(r'%\d*\$?[sdf]', base[name])

apks = {}
for flavor in ('consumer', 'developer'):
    for path in (Path(f'app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk'),
                 Path(f'app/build/outputs/apk/androidTest/{flavor}/debug/app-{flavor}-debug-androidTest.apk')):
        apks[str(path)] = {'bytes': path.stat().st_size, 'sha256': digest(path)}
summary = {'sourceManifestSha': after['sourceManifestSha'], 'reports': reports,
           'newUniqueJvmCases': 8, 'localizedKeys': 7, 'apks': apks, 'deviceStatus': 'not requested'}
(out / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary, indent=2))
