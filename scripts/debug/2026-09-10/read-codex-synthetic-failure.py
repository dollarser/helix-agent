"""Read only the opt-in debug exception-type/stack file, never Runtime credentials or request bodies."""
import argparse
import os
from pathlib import Path
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
assert not a.serial.startswith('emulator-')
adb = Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb'
r = subprocess.run([str(adb), '-s', a.serial, 'exec-out', 'run-as', 'com.helix.runtime.cli',
                    'cat', 'cache/codex-synthetic-failure.txt'], capture_output=True, timeout=30)
assert r.returncode == 0, r.stderr.decode()
a.output.write_bytes(r.stdout)
print(r.stdout.decode())
