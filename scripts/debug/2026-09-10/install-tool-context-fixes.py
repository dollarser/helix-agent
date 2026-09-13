"""Summarize targeted JVM results and cover-install only on the explicitly selected physical phone."""
from pathlib import Path
import os, subprocess, xml.etree.ElementTree as ET
names = {'SummaryOutputBudgetTest', 'ToolModelResultTest', 'TurnBudgetTrackerTest', 'ProviderContextSettingsTest', 'ChatContextUsageTest'}
totals = [0,0,0]
for path in Path('app/build/test-results/testDeveloperDebugUnitTest').glob('TEST-*.xml'):
    if path.stem.split('.')[-1] not in names: continue
    root = ET.parse(path).getroot()
    counts = [int(root.get(k, '0')) for k in ('tests','failures','skipped')]
    totals = [a+b for a,b in zip(totals, counts)]
    print(path.stem, counts)
print('tests/failures/skipped:', totals, flush=True)
assert totals[0] >= 17 and totals[1:] == [0,0]
serial = os.environ['HELIX_PHONE_SERIAL']
assert not serial.startswith('emulator-')
adb = str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb')
subprocess.run([adb, '-s', serial, 'install', '-r', 'app/build/outputs/apk/developer/debug/app-developer-debug.apk'], check=True)
subprocess.run([adb, '-s', serial, 'shell', 'am', 'start', '-n', 'com.helix.agent.developer/com.helix.app.MainActivity'], check=True)
