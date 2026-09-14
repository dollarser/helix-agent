"""Locate Helix launcher shortcuts without modifying the launcher's stored layout."""
import os,subprocess,xml.etree.ElementTree as ET,json,argparse
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();assert not a.serial.startswith('emulator-');a.output.mkdir(parents=True,exist_ok=False)
adb=[str(Path(os.environ['ANDROID_HOME'])/'platform-tools/adb'),'-s',a.serial]
def call(*args):return subprocess.check_output([*adb,*args],timeout=30)
remote='/sdcard/helix-launcher-inspect.xml'
try:
 for page in range(8):
  call('shell','uiautomator','dump',remote)
  xml=call('shell','cat',remote);root=ET.fromstring(xml)
  nodes=[{'text':n.get('text'),'bounds':n.get('bounds')} for n in root.iter('node') if 'Helix' in n.get('text','')]
  if nodes:
   (a.output/'page.xml').write_bytes(xml);(a.output/'screen.png').write_bytes(call('exec-out','screencap','-p'));(a.output/'icons.json').write_text(json.dumps(nodes,indent=2));print(json.dumps(nodes));break
  call('shell','input','swipe','1110','1700','140','1700','350')
 else:raise RuntimeError('Helix page not found in bounded scan')
finally:call('shell','rm','-f',remote)
