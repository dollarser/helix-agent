"""Read only visible developer-setting labels related to root/ADB; no setting mutation."""
import os,subprocess,xml.etree.ElementTree as ET
from pathlib import Path
adb=str(Path(os.environ['ANDROID_HOME'])/'platform-tools/adb'); serial=os.environ['HELIX_PHONE_SERIAL']
r=subprocess.run([adb,'-s',serial,'shell','uiautomator','dump','/dev/tty'],capture_output=True,text=True,timeout=15)
a=r.stdout.find('<?xml'); b=r.stdout.rfind('</hierarchy>')
if a>=0 and b>=0:
 root=ET.fromstring(r.stdout[a:b+12])
 for n in root.iter('node'):
  if any(k in n.get('text','').lower() for k in ['root','adb','usb','调试']):
   print({k:n.get(k) for k in ['text','resource-id','bounds','checked']})
