"""Read only Helix process/lifecycle and phase signals during an owned phone probe."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', required=True)
p.add_argument('--output', required=True, type=Path)
a = p.parse_args()
assert not a.serial.startswith('emulator-')
adb = Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb'
def read(*args):
    return subprocess.check_output([str(adb), '-s', a.serial, *args], text=True, timeout=10)

with a.output.open('x') as output:
    for _ in range(20):
        state = {'at': time.time()}
        for package in ('com.helix.agent.developer', 'com.helix.runtime.cli'):
            raw = read('shell', 'dumpsys', 'activity', 'processes', package)
            state[package] = [line.strip() for line in raw.splitlines()
                              if re.search(r'curProcState=|isFrozen=|virtualFreeze:', line)]
        top = read('shell', 'dumpsys', 'activity', 'activities')
        state['helixForeground'] = any('topResumedActivity=' in line and 'com.helix.' in line
                                       for line in top.splitlines())
        output.write(json.dumps(state) + '\n')
        output.flush()
        time.sleep(1)
