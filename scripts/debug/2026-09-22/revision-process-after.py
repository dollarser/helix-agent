#!/usr/bin/env python3
"""Exercise an actual normal-app edit/save/SIGKILL/reopen, then verify durable state."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'scripts'))
from android_process_control import kill_emulator_app

serial, destination = sys.argv[1:]
output = Path(destination)
owner = json.loads((output / 'owner.json').read_text())
assert owner['serial'] == serial
os.kill(owner['pid'], 0)
package = os.environ['HXA215_PACKAGE']
assert package in ('com.helix.agent', 'com.helix.agent.developer')
base = [str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb'), '-s', serial]
runner = package + '.test/com.helix.app.HelixAndroidJUnitRunner'

def adb(*args):
    return subprocess.check_output(base + list(args), text=True, timeout=40)

def nodes(label):
    adb('shell', 'uiautomator', 'dump', '/sdcard/helix-revision.xml')
    raw = adb('shell', 'cat', '/sdcard/helix-revision.xml')
    (output / (label + '.xml')).write_text(raw)
    return list(ET.fromstring(raw).iter('node'))

def tap(node):
    x1,y1,x2,y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))

def wait_text(text, label):
    choices = (text,) if isinstance(text, str) else text
    for _ in range(12):
        found = next((n for n in nodes(label)
                      if n.get('text') in choices or n.get('content-desc') in choices), None)
        if found is not None: return found
        time.sleep(.5)
    raise RuntimeError('UI text not found: ' + repr(choices))

def open_session(title):
    adb('shell', 'am', 'start', '-n', package + '/com.helix.app.MainActivity')
    time.sleep(1)
    # A freshly-started process lands on the session list; select the durable session.
    tap(wait_text(title, 'session-list'))

open_session('REVISION-RECOVERY')
tap(wait_text('RECOVER-DRAFT-215', 'normal-editor-before'))
# Wait for focus/IME layout, then observe an empty field before typing the replacement.
# Key events alone are not a receipt: a focus transition can drop the first MOVE_END.
for attempt in range(3):
    fields = [n for n in nodes('normal-editor-focus') if n.get('class') == 'android.widget.EditText']
    assert len(fields) == 1
    if fields[0].get('focused') != 'true': tap(fields[0])
    adb('shell', 'input', 'keyevent', '123')
    time.sleep(.3)
    adb('shell', 'input', 'keyevent', *(['67'] * (len(fields[0].get('text', '')) + 1)))
    cleared = [n for n in nodes('normal-editor-cleared') if n.get('class') == 'android.widget.EditText']
    if len(cleared) == 1 and cleared[0].get('text') == '': break
else:
    raise RuntimeError('Could not clear the focused editor before replacement')
adb('shell', 'input', 'text', 'NORMAL-PROCESS-EDIT-215')
wait_text('NORMAL-PROCESS-EDIT-215', 'normal-editor-written')
time.sleep(1)
view = nodes('normal-editor-saved')
assert any(n.get('text') in ('草稿已保存', 'Draft saved') for n in view)
pid = int(adb('shell', 'pidof', package).strip())
kill_emulator_app(base, package, pid)
open_session('REVISION-RECOVERY')
wait_text('NORMAL-PROCESS-EDIT-215', 'normal-editor-restored')
new_pid = int(adb('shell', 'pidof', package).strip())
assert new_pid != pid
# Dismiss editor without discarding, return to sessions, inspect the committed-before-receipt fixture.
adb('shell', 'input', 'keyevent', '4')
tap(wait_text(('会话列表', 'Session list'), 'after-back'))
tap(wait_text('REVISION-ACCEPTED', 'accepted-session-list'))
wait_text('NEW-ACCEPTED', 'accepted-no-replay')
assert not any(n.get('text') == 'OLD-ACCEPTED' for n in nodes('accepted-effective-history'))
(output / 'normal-process.json').write_text(json.dumps({'beforePid':pid, 'afterPid':new_pid,
    'normalActivity':True, 'draftRestored':True, 'acceptedHistoryVisible':True}, indent=2))
adb('logcat', '-c')
result = adb('shell', 'am', 'instrument', '-w', '-e', 'class',
    'com.helix.app.ui.MessageEditRecoveryDeviceTest#verifyRevisionRecovery', runner)
(output / 'verify-instrumentation.txt').write_text(result)
assert 'OK (1 test)' in result and 'FAILURES!!!' not in result, result
(output / 'verify-logcat.txt').write_text(adb('logcat', '-d', '-s', 'TestRunner'))
print('Normal process edit/save/kill/reopen verified', flush=True)
