"""Inspect only subscription presentation; never click login/logout or read vault data."""
import argparse, json, os, subprocess, uuid
from pathlib import Path
import xml.etree.ElementTree as ET
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
assert not a.serial.startswith('emulator-')
a.output.mkdir(parents=True,exist_ok=False)
adb=[str(Path(os.environ['ANDROID_HOME'])/'platform-tools/adb'),'-s',a.serial]
def call(*args):return subprocess.check_output([*adb,*args],timeout=30)
results={}
for activity in ['CliRuntimeHomeActivity','CodexLoginActivity','CopilotLoginActivity','ClaudeLoginActivity','GrokLoginActivity']:
 launch=call('shell','am','start','-W','-n','com.helix.runtime.cli/.app.'+activity).decode();assert 'Status: ok' in launch
 remote='/sdcard/helix-ui-'+uuid.uuid4().hex+'.xml'
 try:
  call('shell','uiautomator','dump',remote)
  xml=call('shell','cat',remote)
  (a.output/(activity+'.xml')).write_bytes(xml)
  (a.output/(activity+'.png')).write_bytes(call('exec-out','screencap','-p'))
  nodes=ET.fromstring(xml)
  results[activity]=[{'text':n.get('text'),'bounds':n.get('bounds')} for n in nodes.iter('node') if n.get('text')]
 finally:call('shell','rm','-f',remote)
call('shell','am','start','-W','-n','com.helix.runtime.cli/.app.CliRuntimeHomeActivity')
(a.output/'result.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
print('Captured five subscription screens; login actions were not clicked.')
