"""Inspect navigation/provider labels only, excluding conversation text and credentials."""
from pathlib import Path
import os,subprocess,shlex,xml.etree.ElementTree as E,re
adb=str(Path(os.environ['ANDROID_HOME'])/'platform-tools/adb');serial=os.environ['HELIX_PHONE_SERIAL'];assert not serial.startswith('emulator-')
def shell(*a):return subprocess.check_output([adb,'-s',serial,'shell',shlex.join(a)],text=True,timeout=30)
if os.environ.get('HELIX_OPEN')=='1':shell('am','start','-W','-n','com.helix.agent.developer/com.helix.app.MainActivity')
if os.environ.get('HELIX_SCROLL')=='1':shell('input','swipe','650','2400','650','650','400')
p='/data/local/tmp/helix-provider-ui.xml';shell('uiautomator','dump',p)
try:
 root=E.fromstring(shell('cat',p))
 for n in root.iter('node'):
  t=n.get('text','');desc=n.get('content-desc','')
  if n.get('package')!='com.helix.agent.developer':continue
  if os.environ.get("HELIX_SETTINGS_LABELS") == "1" or desc or re.search(r'Provider|Codex|模型|连接|测试|失败|成功|设置|菜单|订阅|扩展|提供商|服务|供应|连接器',t):print(t[:180] if not n.get('class','').endswith('EditText') else '[input]',desc,n.get('bounds'))
  if os.environ.get('HELIX_CLICK') in [t,desc] and os.environ.get('HELIX_CLICK'):
   a,b,c,d=map(int,re.findall(r'\d+',n.get('bounds')));shell('input','tap',str((a+c)//2),str((b+d)//2));break
finally:shell('rm','-f',p)
