"""Report exact current task outputs; a report file alone is not proof of a fresh test run."""
from pathlib import Path
import hashlib
import json
import xml.etree.ElementTree as ET

ROOTS = {
    'consumer': 'app/build/test-results/testConsumerDebugUnitTest',
    'developer': 'app/build/test-results/testDeveloperDebugUnitTest',
}
FOCUSED = {'ProviderModelSelectionTest', 'ProviderModelEvidenceTest'}
FIELDS = ('tests', 'failures', 'errors', 'skipped')
results = {}
for name, directory in ROOTS.items():
    files = sorted(Path(directory).glob('TEST-*.xml'))
    if not files:
        raise SystemExit(f'Missing test reports: {directory}')
    totals = dict.fromkeys(FIELDS, 0)
    focused = []
    for file in files:
        root = ET.parse(file).getroot()
        counts = {key: int(root.get(key, '0')) for key in FIELDS}
        for key in FIELDS:
            totals[key] += counts[key]
        suite = root.get('name', '').rsplit('.', 1)[-1]
        if suite in FOCUSED:
            focused.append({'suite': suite, 'timestamp': root.get('timestamp'), **counts})
    if {item['suite'] for item in focused} != FOCUSED:
        raise SystemExit(f'Missing focused suite: {name}')
    if totals['failures'] or totals['errors'] or any(item['skipped'] for item in focused):
        raise SystemExit(f'Failed or skipped focused verification: {name}')
    results[name] = {'directory': directory, **totals, 'focused': focused}

sources = [
    'app/src/main/kotlin/com/helix/app/provider/ProviderSelectedModels.kt',
    'app/src/main/kotlin/com/helix/app/provider/ProviderModelEvidenceStore.kt',
    'app/src/main/kotlin/com/helix/app/provider/ProviderConnectionProbe.kt',
    'app/src/main/kotlin/com/helix/app/provider/ProviderService.kt',
    'app/src/main/kotlin/com/helix/app/provider/ProviderUiModels.kt',
    'app/src/main/kotlin/com/helix/app/ui/ProviderModelsDialog.kt',
    'app/src/main/kotlin/com/helix/app/ui/ComposerModelMenu.kt',
]
result = {
    'note': 'Execution and coverage are established by recorded Gradle commands, not counts alone.',
    'reports': results,
    'source_sha256': {file: hashlib.sha256(Path(file).read_bytes()).hexdigest() for file in sources},
}
output = Path('build/provider-model-management-2026-09-30/host-summary.json')
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(results, ensure_ascii=False, indent=2))
