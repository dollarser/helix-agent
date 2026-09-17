"""Inspect only the latest session in a previously captured private database."""
import argparse
import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', required=True)
p.add_argument('--snapshot', type=Path, required=True)
a = p.parse_args()
assert a.snapshot.resolve().is_relative_to(Path('build').resolve())
db = sqlite3.connect(a.snapshot / 'helix.db')
db.row_factory = sqlite3.Row
session = db.execute('select * from sessions order by createdAt desc limit 1').fetchone()
sid = session['id']
print('MODEL', session['modelId'])
for table in ['turns', 'messages']:
    rows = list(db.execute(f'select * from {table} where sessionId=? order by rowid', (sid,)))
    for row in rows:
        item = dict(row)
        if table == 'messages':
            ref = json.loads(item.pop('contentRef')) if item['contentRef'] else None
            if ref:
                assert ref['path'] == f"content/{ref['sha256'][:2]}/{ref['sha256']}"
                raw = subprocess.check_output(['adb', '-s', a.serial, 'exec-out', 'run-as',
                    'com.helix.agent.developer', 'cat', 'files/helix-content/' + ref['path']])
                assert hashlib.sha256(raw).hexdigest() == ref['sha256']
                out = a.snapshot / ('message-' + str(item['sequence']) + '.txt')
                out.write_bytes(raw)
                out.chmod(0o600)
                item['body'] = raw.decode()[:2400]
                item['bytes'] = len(raw)
        print(table, json.dumps(item, ensure_ascii=False))
for table in ['model_calls', 'tool_calls']:
    for row in db.execute(f'select x.* from {table} x join turns t on t.id=x.turnId where t.sessionId=? order by x.rowid', (sid,)):
        item = dict(row)
        item.pop('providerSnapshot', None)
        item.pop('promptSections', None)
        if 'argsJson' in item:
            item['argsJson'] = item['argsJson'][:1800]
        print(table, json.dumps(item, ensure_ascii=False))
for row in db.execute('select r.* from tool_results r join tool_calls c on c.callId=r.toolCallId join turns t on t.id=c.turnId where t.sessionId=?', (sid,)):
    item = dict(row)
    item.pop('contentRef', None)
    print('tool_results', json.dumps(item, ensure_ascii=False)[:2200])
