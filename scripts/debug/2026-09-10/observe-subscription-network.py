"""Record only Helix UID network state and its bound service during a physical-device probe."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import time

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
assert not a.serial.startswith('emulator-')
adb = Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb'
def shell(*args):
    return subprocess.check_output([str(adb), '-s', a.serial, 'shell', *args], text=True, timeout=10)
uids = []
for package in ['com.helix.agent.developer', 'com.helix.runtime.cli']:
    output = shell('pm', 'list', 'packages', '-U', package)
    uids += re.findall(r'uid:(\d+)', output)
with a.output.open('x') as log:
    for _ in range(30):
        log.write(f'\ntime={time.time()}\n')
        for line in shell('dumpsys', 'netpolicy').splitlines():
            if any(f'UID={uid} ' in line for uid in uids):
                log.write(line + '\n')
        log.write(shell('dumpsys', 'activity', 'services', 'com.helix.runtime.cli'))
        log.flush()
        time.sleep(1)
