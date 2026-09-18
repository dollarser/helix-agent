"""Owned-runner follow-up: no instrumentation force-stop during the main-process death window."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time

serial, output = sys.argv[1:]
output = Path(output)
adb = str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb')
package = 'com.helix.agent.developer'

def shell(*args):
    return subprocess.check_output([adb, '-s', serial, 'shell', *args], text=True, stderr=subprocess.PIPE).strip()

def read(path):
    return shell('run-as', package, 'cat', path)

token = read('no_backup/detached-probe-permit')
assert len(token) == 36
shell('am', 'start', '-W', '-n', package + '/com.helix.app.proot.DetachedOwnerProbeActivity', '--es', 'token', token)
deadline = time.monotonic() + 30
while True:
    try:
        lines = read('no_backup/detached-probe-started').splitlines()
        if len(lines) == 3:
            break
    except subprocess.CalledProcessError:
        pass
    assert time.monotonic() < deadline, 'Probe start deadline'
    time.sleep(.1)
main_pid, job, execution = lines
runtime_pid = shell('pidof', package + ':proot')
assert main_pid.isdigit() and runtime_pid.isdigit() and main_pid != runtime_pid
shell('run-as', package, 'touch', 'no_backup/detached-probe-kill')
for attempt in range(100):
    processes = shell('ps', '-A', '-o', 'PID').split()
    if main_pid not in processes:
        break
    time.sleep(.05)
else:
    raise AssertionError('Original main PID did not die')
deadline = time.monotonic() + 30
while True:
    assert shell('pidof', package + ':proot') == runtime_pid, 'Runtime PID changed after main-only kill'
    record = json.loads(read('files/runtime/jobs/' + job + '/record.json'))
    if record['state'] not in ('PENDING', 'RUNNING'):
        break
    assert time.monotonic() < deadline, 'Original job completion deadline'
    time.sleep(.1)
assert record['state'] == 'SUCCEEDED', record
assert record['executionId'] == execution and record['outputManifestSha256']
(output / 'owner-death.json').write_text(json.dumps({'mainPid': main_pid, 'runtimePid': runtime_pid, 'record': record}, indent=2))
print('Main-only death: original Runtime PID and Job survived; terminal proof persisted')
