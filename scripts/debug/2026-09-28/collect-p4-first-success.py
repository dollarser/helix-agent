#!/usr/bin/env python3
"""Copy the P4 app-private summary while the owned emulator is still alive."""
from pathlib import Path
import os
import subprocess
import sys

serial, output = sys.argv[1:]
out = Path(output)
adb = str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb')
pkg = 'com.helix.agent.developer'

def run(*args):
    return subprocess.run([adb, '-s', serial, *args], check=True, capture_output=True, timeout=30).stdout

(out / 'first-success-evidence.txt').write_bytes(
    run('exec-out', 'run-as', pkg, 'cat', 'files/p4-first-success/evidence.txt')
)
(out / 'first-success-marker.properties').write_bytes(
    run('exec-out', 'run-as', pkg, 'cat', 'no_backup/p4-first-success.properties')
)
