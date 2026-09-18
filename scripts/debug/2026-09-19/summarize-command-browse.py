#!/usr/bin/env python3
"""Check command-detail instrumentation results and immutable per-flavor APK identities."""
import json
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[3]
prefix = sys.argv[1]
rows = []
identities = {}
for api in (29, 36):
    for flavor, expected in (('consumer', 5), ('developer', 8)):
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
(root / 'build' / f'{prefix}-summary.json').write_text(json.dumps(dict(tests=26, runs=rows), indent=2) + '\n')
print('26 command-detail instrumentation cases passed; four owned emulators closed; APK hashes match per flavor')
