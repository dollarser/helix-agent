"""Select failed or crashed classes without overwriting the original complete run."""
import json
from pathlib import Path
import sys

source, destination = map(Path, sys.argv[1:])
rows = [json.loads(path.read_text()) for path in sorted((source / 'results').glob('*.json'))]
selected = [row['class'] for row in rows if row['verdict'] not in ('PASS', 'SKIP / ASSUMPTION', 'PHASE_RUNNER_REQUIRED')]
assert selected
destination.write_text('\n'.join(selected) + '\n')
print(f'{len(selected)} classes selected')
