"""Regression: matrix must propagate every failed quadrant, without starting devices."""
import json
from pathlib import Path
import subprocess
import tempfile
import sys

source = (Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).with_name('run-hxa192-matrix.sh')).resolve()
for exit_code in (0, 1, 10, 99, 100, 127, 137, 255):
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        stub = root / 'scripts/debug/2026-09-17/hxa192/run-hxa192-device.sh'
        stub.parent.mkdir(parents=True)
        stub.write_text(f'#!/bin/sh\nif [ "$1-$2" = consumer-29 ]; then exit {exit_code}; fi\nexit 0\n')
        stub.chmod(0o755)
        result = subprocess.run(['sh', str(source), str(root / 'output')], cwd=root, capture_output=True, text=True)
        assert result.returncode == (0 if exit_code == 0 else 1), (exit_code, result.stdout, result.stderr)
        rows = [json.loads(line) for line in (root / 'output/manifest.jsonl').read_text().splitlines()]
        assert len(rows) == 4 and rows[0]['exit'] == exit_code
        assert all(row['exit'] == 0 for row in rows[1:])
print('PASS: 8 exit-code cases, all four quadrants retained, no device started')
