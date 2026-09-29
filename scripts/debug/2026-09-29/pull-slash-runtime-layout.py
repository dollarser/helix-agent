import os
from pathlib import Path
import subprocess
import sys

adb = str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb')
result = subprocess.run([adb, '-s', sys.argv[1], 'exec-out', 'run-as', 'com.helix.agent.developer',
                         'cat', 'cache/proot-repair-layout.png'], check=True, capture_output=True)
(Path(sys.argv[2]) / 'proot-repair-layout.png').write_bytes(result.stdout)
