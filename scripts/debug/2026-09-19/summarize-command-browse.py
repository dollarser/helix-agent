#!/usr/bin/env python3
"""Check command-detail instrumentation results and immutable per-flavor APK identities."""
import json
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[3]
prefix = sys.argv[1]
suite = sys.argv[2] if len(sys.argv) > 2 else 'commands'
counts = {'commands': (5, 13), 'tasks': (6, 6)}[suite]
rows = []
identities = {}
for api in (29, 36):
    for flavor, expected in zip(('consumer', 'developer'), counts):
        directory = root / 'build' / f'{prefix}-{flavor}-api{api}'
        output = (directory / 'instrumentation.txt').read_text()
        if re.findall(r'OK \((\d+) tests?\)', output) != [str(expected)]:
            raise SystemExit(f'Unexpected test count: {directory.name}')
        if any(marker in output for marker in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed')):
            raise SystemExit(f'Instrumentation failed: {directory.name}')
        closed = json.loads((directory / 'closed.json').read_text())
        if closed['exit'] != 0:
            raise SystemExit(f'Owned emulator did not close normally: {directory.name}')
        artifacts = json.loads((directory / 'artifacts.json').read_text())
        if flavor in identities and artifacts != identities[flavor]:
            raise SystemExit(f'APK changed across APIs: {flavor}')
        identities[flavor] = artifacts
        rows.append(dict(api=api, flavor=flavor, tests=expected, artifacts=artifacts, closed=closed))
total = 2 * sum(counts)
(root / 'build' / f'{prefix}-summary.json').write_text(json.dumps(dict(tests=total, runs=rows), indent=2) + '\n')
print(f'{total} {suite} instrumentation cases passed; four owned emulators closed; APK hashes match per flavor')
