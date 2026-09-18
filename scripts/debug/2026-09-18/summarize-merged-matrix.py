"""Validate every merged-source matrix result and count assumptions separately."""
from pathlib import Path
import hashlib
import json
import re

root = Path(__file__).resolve().parents[3]
output = root / 'build/merge-193-195-batch-b'
records = []
for api in (29, 36):
    for flavor in ('consumer', 'developer'):
        artifacts = {}
        for kind, path in {
            'app': f'app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk',
            'test': f'app/build/outputs/apk/androidTest/{flavor}/debug/app-{flavor}-debug-androidTest.apk',
        }.items():
            with (root / path).open('rb') as source:
                artifacts[kind] = hashlib.file_digest(source, 'sha256').hexdigest()
        phases = [('recovery', 15), ('readiness', 7)]
        if flavor == 'developer':
            phases.append(('logs-runtime', 8))
        for phase, expected in phases:
            evidence = output / f'{flavor}-api{api}-{phase}'
            result = (evidence / 'instrumentation.txt').read_text()
            assert f'OK ({expected} tests)' in result, evidence
            assert json.loads((evidence / 'closed.json').read_text())['exit'] == 0
            assert json.loads((evidence / 'artifacts.json').read_text()) == artifacts
            logs = (evidence / 'test-logcat.txt').read_text().splitlines()
            final = [line for line in logs if 'run finished:' in line][-1]
            assert f'{expected} tests, 0 failed,' in final
            pid = final.split()[2]
            assumptions = [line for line in logs if len(line.split()) > 2 and line.split()[2] == pid
                           and 'assumption failed:' in line]
            skipped = 1 if flavor == 'consumer' and phase == 'readiness' else 0
            assert len(assumptions) == skipped, (evidence, assumptions)
            records.append({'api': api, 'flavor': flavor, 'phase': phase, 'passed': expected - skipped,
                            'skipped': skipped, 'seconds': float(re.search(r'Time: ([\d.]+)', result)[1]),
                            'artifacts': artifacts})
assert len(records) == 10
summary = {'passed': sum(row['passed'] for row in records),
           'skipped': sum(row['skipped'] for row in records), 'failed': 0, 'runs': records}
(output / 'matrix-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(f"Merged matrix: {summary['passed']} passed, {summary['skipped']} conditional skips, 0 failed; 10 owned runs")
