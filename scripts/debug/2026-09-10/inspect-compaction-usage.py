"""Read-only call accounting and model metadata. Never prints message bodies or credentials."""
import os, subprocess, shlex, json
from pathlib import Path
adb = str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb')
serial = os.environ['HELIX_PHONE_SERIAL']
assert not serial.startswith('emulator-')
def shell(*args):
    return subprocess.check_output([adb, '-s', serial, 'shell', shlex.join(args)], text=True, timeout=15)
def query(sql):
    return json.loads(shell('run-as', 'com.helix.agent.developer', '/system/bin/sqlite3', '-readonly', '-json', 'databases/helix.db', sql) or '[]')
print(json.dumps(query('select t.state,t.errorCode,t.stepCount,c.state as callState,c.usage,c.providerSnapshot from turns t left join model_calls c on c.turnId=t.id order by t.startedAt desc limit 8;')))
print(shell('run-as', 'com.helix.agent.developer', 'find', 'files', '-name', 'model-metadata-v1-*'))
import xml.etree.ElementTree as ET
prefs = ET.fromstring(shell('run-as', 'com.helix.agent.developer', 'cat', 'shared_prefs/helix-ui.xml'))
for entry in prefs:
    if entry.get('name','').startswith(('model-metadata-v1-', 'context-v1-')):
        print(json.dumps({'kind': entry.get('name').split('-v1-')[0], 'metadata': entry.text}))
