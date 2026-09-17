"""Read only budget preferences and audit metadata from a private session snapshot."""
import argparse
import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', required=True)
p.add_argument('--snapshot', type=Path, required=True)
a = p.parse_args()
assert not a.serial.startswith('emulator-')
assert a.snapshot.resolve().is_relative_to(Path('build').resolve())
raw = subprocess.check_output(['adb', '-s', a.serial, 'exec-out', 'run-as',
    'com.helix.agent.developer', 'cat', 'shared_prefs/helix-ui.xml'])
prefs = {entry.attrib.get('name', ''): entry.text for entry in ET.fromstring(raw)}
print('CURRENT_RUN_CONTROL', prefs.get('run_control_v1'))
db = sqlite3.connect(a.snapshot / 'helix.db')
db.row_factory = sqlite3.Row
sid = db.execute('select id from sessions order by createdAt desc limit 1').fetchone()[0]
session = db.execute('select * from sessions where id=?', (sid,)).fetchone()
for row in db.execute('select providerSnapshot from model_calls where turnId in (select id from turns where sessionId=?) order by rowid desc limit 1', (sid,)):
    snapshot = json.loads(row[0])
    print('SNAPSHOT_KEYS', list(snapshot))
    encoded = ''.join(f'{len(value)}:{value}' for value in (session['providerId'], snapshot['endpoint'], session['modelId']))
    print('CURRENT_MODEL_CONTEXT', prefs.get('context-v1-' + hashlib.sha256(encoded.encode()).hexdigest(), 'default'))
    for key in ('budgets', 'contextSettings', 'maxInputTokens', 'maxTotalTokens'):
        if key in snapshot:
            print(key, snapshot[key])
for row in db.execute("select name from sqlite_master where type='table' and name like '%audit%'"):
    table = row[0]
    columns = [r[1] for r in db.execute(f'pragma table_info({table})')]
    print(table, columns)
    if 'sessionId' in columns:
        for event in db.execute(f'select * from {table} where sessionId=? order by rowid', (sid,)):
            print('AUDIT', json.dumps(dict(event), ensure_ascii=False))
    else:
        for event in db.execute(f'select * from {table} where timestamp between (select min(startedAt) from turns where sessionId=?) and (select max(endedAt) from turns where sessionId=?) and type not like ? order by rowid', (sid, sid, 'cli.%')):
            print('AUDIT', json.dumps(dict(event), ensure_ascii=False))
