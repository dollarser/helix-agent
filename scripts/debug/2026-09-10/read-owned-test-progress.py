"""Read only the log of an emulator whose runner process is still live."""
import json
import os
from pathlib import Path
import subprocess
import sys
record = json.loads((Path(sys.argv[1]) / 'owner.json').read_text())
os.kill(record['pid'], 0)
adb = str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb')
result = subprocess.run([adb, '-s', record['serial'], 'logcat', '-d', '-s', 'TestRunner'], text=True, capture_output=True, check=True)
lines = int(sys.argv[2]) if len(sys.argv) > 2 else 40
print('\n'.join(result.stdout.splitlines()[-lines:]))
