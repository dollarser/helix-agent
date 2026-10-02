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
    # A just-created API36 window may temporarily expose no accessibility root.
    # Remove the old dump before each attempt so a stale snapshot cannot pass.
    for attempt in range(5):
        adb("shell", "rm", "-f", "/sdcard/helix-revision.xml")
        result = adb("shell", "uiautomator", "dump", "/sdcard/helix-revision.xml")
        if "UI hierchary dumped to:" not in result:
            (output / (label + f"-dump-{attempt}.txt")).write_text(result)
            time.sleep(0.5)
            continue
        raw = adb("shell", "cat", "/sdcard/helix-revision.xml")
        (output / (label + ".xml")).write_text(raw)
        return list(ET.fromstring(raw).iter("node"))
    raise RuntimeError("No fresh accessibility snapshot for " + label)


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

def open_history(label):
    tap(wait_text(('应用导航', 'App navigation'), label + '-navigation'))
    tap(wait_text(('全部会话', 'All conversations'), label + '-history'))

def open_session(title, restored_text=None):
    failure = None
    for attempt in range(2):
        adb('shell', 'am', 'start', '-W', '-n', package + '/com.helix.app.MainActivity')
        time.sleep(1)
        try:
            # Conversation-first startup may restore this editor immediately. Its modal
            # intentionally hides navigation; verify its exact text and durable owner later.
            if restored_text is not None and any(n.get('class') == 'android.widget.EditText' and
                    n.get('text') == restored_text for n in nodes(f'restored-editor-{attempt}')):
                return
            # ActivityManager can briefly retain the killed task on API36 and report
            # that the launch was delivered even though Launcher is still visible.
            open_history(f'open-session-{attempt}')
            session = wait_text(title, f'session-list-{attempt}')
        except RuntimeError as error:
            failure = error
            continue
        tap(session)
        return
    raise failure or RuntimeError('Could not start the normal application activity')

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
# The current editor debounces persistence (250ms) and has no saved-status label.
# Reopened UI plus verifyRevisionRecovery below prove the exact saved text, revision,
# original message and unchanged Turn count; elapsed time alone is not a pass.
time.sleep(2)
view = nodes('normal-editor-before-kill')
assert any(n.get('text') == 'NORMAL-PROCESS-EDIT-215' for n in view)
pid = int(adb('shell', 'pidof', package).strip())
kill_emulator_app(base, package, pid)
open_session('REVISION-RECOVERY', restored_text='NORMAL-PROCESS-EDIT-215')
wait_text('NORMAL-PROCESS-EDIT-215', 'normal-editor-restored')
new_pid = int(adb('shell', 'pidof', package).strip())
assert new_pid != pid
# Dismiss editor without discarding, return to sessions, inspect the committed-before-receipt fixture.
for _ in range(2):
    # An active IME consumes the first Back; only the next Back dismisses the editor.
    adb('shell', 'input', 'keyevent', '4')
    time.sleep(1)
    back = next((n for n in nodes('after-back')
                 if n.get('content-desc') in ('应用导航', 'App navigation')), None)
    if back is not None:
        break
else:
    raise RuntimeError('Revision editor did not remain closed after dismissing the IME and dialog')
open_history('accepted')
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
