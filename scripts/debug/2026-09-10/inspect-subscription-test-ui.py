"""Inspect subscription test UI only; never print credential fields or conversation text."""
import os,subprocess,shlex,xml.etree.ElementTree as E,re
from pathlib import Path
adb=str(Path(os.environ['ANDROID_HOME'])/'platform-tools/adb'); serial=os.environ['HELIX_PHONE_SERIAL']; assert not serial.startswith('emulator-')
def shell(*a):return subprocess.check_output([adb,'-s',serial,'shell',shlex.join(a)],text=True,timeout=30)
if os.environ.get('HELIX_OPEN')=='1':shell('am','start','-W','-n','com.helix.runtime.cli/com.helix.runtime.cli.app.CodexLoginActivity')
p='/data/local/tmp/helix-test-status.xml'
shell('uiautomator','dump',p)
try:
 root=E.fromstring(shell('cat',p))
 for n in root.iter('node'):
  t=n.get('text','')
  if t and n.get('package')=='com.helix.runtime.cli' and not n.get('class','').endswith('EditText'):
   print(t,n.get('bounds'))
  if os.environ.get('HELIX_CLICK') and os.environ['HELIX_CLICK']==t:
   a,b,c,d=map(int,re.findall(r'\d+',n.get('bounds')));shell('input','tap',str((a+c)//2),str((b+d)//2))
finally:shell('rm','-f',p)
