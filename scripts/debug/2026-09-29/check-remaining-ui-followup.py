"""Collect the legal surface and rerun its assertions at 320dp / large font on the owned AVD."""
import os
from pathlib import Path
import subprocess
import sys

adb = str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb')
serial, output = sys.argv[1], Path(sys.argv[2])
def device(*args):
    return subprocess.check_output([adb, '-s', serial, *args], text=True)
def capture(name):
    result = subprocess.check_output([adb, '-s', serial, 'exec-out', 'run-as',
                                     'com.helix.agent.developer', 'cat', 'cache/proot-legal-layout.png'])
    (output / name).write_bytes(result)
capture('legal-default.png')
try:
    device('shell', 'wm', 'size', '960x1920')
    device('shell', 'wm', 'density', '480')
    device('shell', 'settings', 'put', 'system', 'font_scale', '1.5')
    result = device('shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                    'com.helix.app.proot.ProotRepairLayoutDeviceTest#legalPageKeepsTitleAndCloseInsideSystemBars',
                    'com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner')
    (output / 'legal-large-font.txt').write_text(result)
    if 'OK (1 test)' not in result:
        raise RuntimeError('Large font legal test failed')
    capture('legal-320dp-large-font.png')
finally:
    device('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
    device('shell', 'wm', 'size', 'reset')
    device('shell', 'wm', 'density', 'reset')
