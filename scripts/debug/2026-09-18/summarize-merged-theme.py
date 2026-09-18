#!/usr/bin/env python3
"""Validate actual mode and owned-process closure for the merged API36 theme runs."""
import json
from pathlib import Path
import re

root = Path(__file__).resolve().parents[3]
for flavor in ('consumer', 'developer'):
    directory = root / 'build' / f'merged-theme-{flavor}-api36'
    assert json.loads((directory / 'closed.json').read_text())['exit'] == 0
    for mode, expected in (('light', 'no'), ('dark', 'yes')):
        assert f'Night mode: {expected}' in (directory / f'{mode}-uimode.txt').read_text()
        output = (directory / f'{mode}-instrument.txt').read_text()
        assert re.findall(r'OK \((\d+) tests?\)', output) == ['6']
        assert 'FAILURES!!!' not in output
        assert 'process crashed' in (directory / f'{mode}-setup.txt').read_text().lower()
        assert (directory / f'{mode}-setup-pid.txt').read_text().strip().isdigit()
print('24 theme verification tests passed; four process-death setups; both owned emulators closed')
