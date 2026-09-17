"""Read-only device inspection; private snapshots stay under ignored build/.

Requires an explicit physical-device serial. Does not stop, clear or install apps.
Only prints latest session execution metadata; bodies are saved separately for review.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', required=True)
p.add_argument('--output', required=True, type=Path)
p.add_argument('--package', default='com.helix.agent.developer')
args = p.parse_args()
assert not args.serial.startswith('emulator-')
assert args.output.resolve().is_relative_to(Path('build').resolve())
args.output.mkdir(parents=True, exist_ok=False, mode=0o700)

def read(path):
    r = subprocess.run(['adb', '-s', args.serial, 'exec-out', 'run-as', args.package,
                        'cat', path], capture_output=True, check=True)
    return r.stdout

paths = ['databases/helix.db', 'databases/helix.db-wal']
previous = None
for attempt in range(4):
    snapshot = {path: read(path) for path in paths}
    digest = {path: hashlib.sha256(data).hexdigest() for path, data in snapshot.items()}
    if digest == previous:
        break
    previous = digest
else:
    raise RuntimeError('Database is changing; no stable read-only snapshot obtained')
for path, data in snapshot.items():
    target = args.output / Path(path).name
    target.write_bytes(data)
    target.chmod(0o600)
db = sqlite3.connect(args.output / 'helix.db')
db.row_factory = sqlite3.Row
assert db.execute('pragma integrity_check').fetchone()[0] == 'ok'
tables = ['sessions', 'turns', 'messages', 'model_calls', 'tool_calls', 'tool_results']
print(json.dumps({table: [r['name'] for r in db.execute(f'pragma table_info({table})')]
                  for table in tables}))
for table in ['sessions', 'turns']:
    rows = [dict(r) for r in db.execute(f'SELECT * FROM {table} ORDER BY rowid DESC LIMIT 3')]
    # Avoid printing user titles and source text during discovery.
    print(table, json.dumps([{k: v for k, v in row.items()
                              if k in {'id', 'sessionId', 'state', 'createdAt', 'startedAt',
                                       'endedAt', 'errorCode', 'finishReason', 'stepCount'}}
                             for row in rows]))
db.close()
