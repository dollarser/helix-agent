#!/usr/bin/env python3
"""Summarize actual host reports/artifacts; never converts unrun device tests into passes."""
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'build/execution-concurrency'
SUITES = {
    'tools/framework/build/test-results/test': [
        'ExecutionConcurrencyTest', 'ExecutionOwnershipTest',
        'ExecutionOwnershipCancellationTest', 'EffectFootprintTest', 'ToolSchedulerTest',
    ],
    'runtime/proot-core/build/test-results/test': [
        'RuntimeExecutionCapacityTest', 'JobLogSpoolTest',
    ],
    'app/build/test-results/testConsumerDebugUnitTest': [
        'ProviderFormValidationTest', 'ExecutionOwnershipStoreTest', 'RuntimeOwnerDiskRecoveryTest',
    ],
    'app/build/test-results/testDeveloperDebugUnitTest': [
        'ProviderFormValidationTest', 'ExecutionOwnershipStoreTest', 'RuntimeOwnerDiskRecoveryTest',
        'TerminalStartTransactionTest', 'ForegroundProotOwnershipTest',
    ],
    'feature/browser/build/test-results/testDebugUnitTest': ['BrowserDownloaderTest'],
}

def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()

reports = {}
for directory, names in SUITES.items():
    for name in names:
        files = list((ROOT / directory).glob(f'TEST-*.{name}.xml'))
        if len(files) != 1:
            raise RuntimeError(f'Expected one report for {directory}/{name}, got {len(files)}')
        path = files[0]
        tree = ET.parse(path).getroot()
        result = {key: int(tree.attrib[key]) for key in ('tests', 'failures', 'errors', 'skipped')}
        result['sha256'] = digest(path)
        result['timestamp'] = tree.attrib.get('timestamp')
        reports[str(path.relative_to(ROOT))] = result
        if result['failures'] or result['errors']:
            raise RuntimeError(f'Failed report: {path.name}')

apks = {}
for flavor in ('consumer', 'developer'):
    for relative in (
        f'app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk',
        f'app/build/outputs/apk/androidTest/{flavor}/debug/app-{flavor}-debug-androidTest.apk',
    ):
        path = ROOT / relative
        apks[relative] = {'bytes': path.stat().st_size, 'sha256': digest(path)}

feedback = {}
for locale in ('values', 'values-en', 'values-zh-rCN'):
    path = ROOT / f'app/src/main/res/{locale}/provider-form-feedback.xml'
    entries = ET.parse(path).getroot().findall('string')
    values = {item.attrib['name']: ''.join(item.itertext()) for item in entries}
    if len(values) != len(entries) or any(not value.strip() for value in values.values()):
        raise RuntimeError(f'Duplicate or empty form feedback: {locale}')
    feedback[locale] = values
if not (feedback['values'].keys() == feedback['values-en'].keys() == feedback['values-zh-rCN'].keys()):
    raise RuntimeError('Form feedback resource keys differ across locales')

before = json.loads((OUT / 'source-final-before.json').read_text())
after = json.loads((OUT / 'source-final-after.json').read_text())
if before['sourceManifestSha'] != after['sourceManifestSha']:
    raise RuntimeError('Source changed during final validation')
summary = {'sourceManifestSha': after['sourceManifestSha'], 'reports': reports,
           'apks': apks, 'formFeedbackKeys': len(feedback['values']), 'deviceStatus': 'not requested'}
(OUT / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary, indent=2))
