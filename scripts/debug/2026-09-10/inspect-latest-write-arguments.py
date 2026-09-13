"""Inspect latest write argument shapes; omit file contents and user messages."""
import os, subprocess, shlex, json
from pathlib import Path
adb = str(Path(os.environ['ANDROID_HOME'])/'platform-tools/adb')
serial = os.environ['HELIX_PHONE_SERIAL']
assert not serial.startswith('emulator-')
def query(sql):
    args = ['run-as','com.helix.agent.developer','/system/bin/sqlite3','-readonly','-json','databases/helix.db',sql]
    return json.loads(subprocess.check_output([adb,'-s',serial,'shell',shlex.join(args)],text=True,timeout=15) or '[]')
rows = query("select c.argsJson,c.state from tool_calls c where c.turnId=(select id from turns order by startedAt desc limit 1) and c.name='write' order by c.rowid;")
for row in rows:
    args = json.loads(row['argsJson'])
    print(json.dumps({'state':row['state'],'args': {k:({'type':type(v).__name__,'length':len(v)} if k=='content' else v) for k,v in args.items()}},ensure_ascii=False))
