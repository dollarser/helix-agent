"""Read only bounded public network diagnostics; no tokens, Wi-Fi identifiers or full logs."""
import os,subprocess,shlex
from pathlib import Path
adb=str(Path(os.environ['ANDROID_HOME'])/'platform-tools/adb');serial=os.environ['HELIX_PHONE_SERIAL'];assert not serial.startswith('emulator-')
def shell(*a):return subprocess.check_output([adb,'-s',serial,'shell',shlex.join(a)],text=True,timeout=25)
for key in ['http_proxy','private_dns_mode','private_dns_specifier']:
 print(key,shell('settings','get','global',key).strip())
print('hosts', '\n'.join(x for x in shell('cat','/system/etc/hosts').splitlines() if 'chatgpt.com' in x or 'auth.openai.com' in x))
print('io logs',shell('logcat','-d','-s','HelixSubscriptionIo:W','*:S'))
