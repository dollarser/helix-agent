#!/usr/bin/env python3
"""Collect only the synthetic image acceptance record from our owned emulator."""
import json
import os
from pathlib import Path
import subprocess
import sys

serial, target = sys.argv[1:]
out = Path(target)
owner = json.loads((out / 'owner.json').read_text())
assert owner['serial'] == serial and serial.startswith('emulator-')
os.kill(owner['pid'], 0)
adb = str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb')
result = subprocess.check_output([adb, '-s', serial, 'exec-out', 'run-as',
    'com.helix.agent.developer', 'cat', 'files/real-tool-vision.json'])
record = json.loads(result)
(out / 'real-tool-vision.json').write_bytes(result)
assert record['passed'], record
