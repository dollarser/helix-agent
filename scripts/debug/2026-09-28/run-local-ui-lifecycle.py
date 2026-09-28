"""Owned emulator follow-up: narrow/large-font UI followed by the real native lifecycle."""
import os
from pathlib import Path
import subprocess
import sys

serial, output = sys.argv[1:]
out = Path(output)
adb = str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb')
runner = 'com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner'

def device(*args):
    return subprocess.check_output([adb, '-s', serial, *args], timeout=120)

for width in (320, 360, 412):
    device('shell', 'wm', 'size', f'{width}x900')
    device('shell', 'wm', 'density', '160')
    device('shell', 'settings', 'put', 'system', 'font_scale', '1.3')
    result = device('shell', 'am', 'instrument', '-w', '-e', 'class',
                    'com.helix.app.ui.LocalModelDialogDeviceTest', runner)
    (out / f'ui-{width}-font130.txt').write_bytes(result)
    assert b'OK (1 test)' in result, result.decode()
device('shell', 'wm', 'size', 'reset')
device('shell', 'wm', 'density', 'reset')
device('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
subprocess.run([sys.executable, str(Path(__file__).with_name('run-real-model-followup.py')), serial, output],
               env={**os.environ, 'HXA222_CLASSES': 'com.helix.app.localmodel.LocalModelLifecycleDeviceTest'},
               check=True, timeout=1000)
