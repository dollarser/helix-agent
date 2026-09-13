"""Report exact host regression counts and update the explicitly selected physical phone."""
from pathlib import Path
import subprocess, os, xml.etree.ElementTree as ET
for glob in ['tools/files/build/test-results/test/TEST-*WriteToolTest.xml','app/build/test-results/testDeveloperDebugUnitTest/TEST-*ToolPurposeTest.xml']:
    files=list(Path('.').glob(glob));assert len(files)==1
    root=ET.parse(files[0]).getroot()
    counts={k:root.get(k,'0') for k in ('tests','failures','errors','skipped')}
    print(root.get('name'), counts,flush=True)
    assert counts['failures']==counts['errors']==counts['skipped']=='0'
adb=str(Path(os.environ['ANDROID_HOME'])/'platform-tools/adb');serial=os.environ['HELIX_PHONE_SERIAL'];assert not serial.startswith('emulator-')
subprocess.run([adb,'-s',serial,'install','-r','app/build/outputs/apk/developer/debug/app-developer-debug.apk'],check=True)
subprocess.run([adb,'-s',serial,'shell','am','start','-n','com.helix.agent.developer/com.helix.app.MainActivity'],check=True)
