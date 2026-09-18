#!/usr/bin/env python3
"""Validate four owned device runs and identical per-flavor APK identities across APIs."""
import json
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[3]
prefix = sys.argv[1]
rows = []
identities = {}
for api in (29, 36):
    for flavor in ('consumer', 'developer'):
        directory = root / 'build' / f'{prefix}-{flavor}-api{api}'
        output = (directory / 'instrumentation.txt').read_text()
        if re.findall(r'OK \((\d+) tests?\)', output) != ['5'] or 'FAILURES!!!' in output:
            raise SystemExit(f'Unexpected test result: {directory.name}')
        closed = json.loads((directory / 'closed.json').read_text())
        if closed['exit'] != 0:
            raise SystemExit(f'Owned emulator exit failed: {directory.name}')
        artifacts = json.loads((directory / 'artifacts.json').read_text())
        if flavor in identities and identities[flavor] != artifacts:
            raise SystemExit(f'APK identity changed between APIs: {flavor}')
        identities[flavor] = artifacts
        rows.append(dict(api=api, flavor=flavor, tests=5, closed=closed, artifacts=artifacts))
destination = root / 'build' / f'{prefix}-summary.json'
destination.write_text(json.dumps(dict(instrumentation=20, runs=rows), indent=2) + '\n')
print(destination)
print('20 instrumentation cases passed; four owned emulators closed; per-flavor APK hashes match')
