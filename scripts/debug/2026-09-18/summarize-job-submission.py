#!/usr/bin/env python3
"""Check owned-device results and immutable APK identity for the submission regression."""
import json
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[3]
prefix = sys.argv[1]
expected = int(sys.argv[2]) if len(sys.argv) > 2 else 17
if expected <= 0:
    raise SystemExit("Expected test count must be positive")
rows = []
for api in (29, 36):
    directory = root / 'build' / f'{prefix}-api{api}'
    output = (directory / 'instrumentation.txt').read_text()
    if re.findall(r'OK \((\d+) tests?\)', output) != [str(expected)]:
        raise SystemExit(f'Unexpected test count: {directory.name}')
    if any(marker in output for marker in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed')):
        raise SystemExit(f'Failed instrumentation: {directory.name}')
    closed = json.loads((directory / 'closed.json').read_text())
    if closed['exit'] != 0:
        raise SystemExit(f'Owned emulator did not close normally: {directory.name}')
    artifacts = json.loads((directory / 'artifacts.json').read_text())
    if rows and artifacts != rows[0]['artifacts']:
        raise SystemExit('APK identity changed between APIs')
    rows.append(dict(api=api, tests=expected, artifacts=artifacts, closed=closed))
destination = root / 'build' / f'{prefix}-summary.json'
destination.write_text(json.dumps(dict(tests=2 * expected, runs=rows), indent=2) + '\n')
print(f'{2 * expected} instrumentation cases passed; both owned emulators closed; APK hashes match')
