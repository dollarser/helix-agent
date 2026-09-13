"""Use the settings UI to migrate the authorized phone DNS workaround; verify without Root or model calls."""
import argparse,os,subprocess,shlex,socket,json,re,xml.etree.ElementTree as ET,time
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial',required=True);p.add_argument('--output',required=True,type=Path);p.add_argument('--hosts-record',required=True,type=Path);a=p.parse_args()
assert not a.serial.startswith('emulator-')
adb=str(Path(os.environ['ANDROID_HOME'])/'platform-tools/adb')
a.output.mkdir(parents=True,exist_ok=False)
def device(*args,timeout=30):
 return subprocess.run([adb,'-s',a.serial,*args],capture_output=True,check=True,timeout=timeout).stdout
def shell(*args):return device('shell',shlex.join(args)).decode().strip()
def tree():
 remote='/data/local/tmp/helix-dns-settings-ui.xml'
 shell('uiautomator','dump',remote)
 xml=shell('cat',remote);shell('rm','-f',remote)
 return ET.fromstring(xml)
def click(node):
 x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
 shell('input','tap',str((x1+x2)//2),str((y1+y2)//2))
def find(predicate):
 for _ in range(3):
  for n in tree().iter('node'):
   if predicate(n):return n
 raise RuntimeError('Visible setting not found')
def open_settings():
 shell('am','start','-W','-n','com.helix.runtime.cli/com.helix.runtime.cli.app.CliRuntimeHomeActivity')
 click(find(lambda n:n.get('text') in ['网络设置 · 域名解析','Network settings · DNS']))
assert shell('getprop','ro.kernel.qemu')!='1'
for apk in ['runtime/cli-app/build/outputs/apk/debug/cli-app-debug.apk','runtime/cli-app/build/outputs/apk/androidTest/debug/cli-app-debug-androidTest.apk']:
 assert b'Success' in device('install','-r',apk,timeout=120)
try:
 ips=sorted({r[4][0] for r in socket.getaddrinfo('chatgpt.com',443,socket.AF_INET)})
 open_settings()
 click(find(lambda n:n.get('resource-id','').endswith(':id/subscription_dns_host')))
 shell('input','text','chatgpt.com')
 click(find(lambda n:n.get('resource-id','').endswith(':id/subscription_dns_addresses')))
 shell('input','text',','.join(ips))
 shell('input','keyevent','KEYCODE_BACK')
 click(find(lambda n:n.get('text') in ['保存／更新映射','Save / update mapping']))
 saved=tree()
 assert any('已保存' in n.get('text','') or n.get('text','').startswith('Saved.') for n in saved.iter('node'))
 assert any(all(ip in n.get('text','') for ip in ips) for n in saved.iter('node'))
 (a.output/'settings.png').write_bytes(device('exec-out','screencap','-p'))
 (a.output/'configuration.json').write_text(json.dumps({'host':'chatgpt.com','addresses':ips,'hours':24,'via':'settings-ui'},indent=2))
 subprocess.run(['python3','scripts/debug/2026-09-10/temporary-phone-hosts.py','--serial',a.serial,'--output',str(a.hosts_record),'--restore'],check=True)
 device('unroot');device('wait-for-device')
 assert shell('id','-u')=='2000'
 assert 'chatgpt.com' not in shell('cat','/system/etc/hosts')
 result=device('shell',shlex.join(['am','instrument','-w','-r','-e','class','com.helix.runtime.cli.app.SubscriptionDnsDeviceTest','-e','helixDnsProbe','true','com.helix.runtime.cli.test/androidx.test.runner.AndroidJUnitRunner']),timeout=60).decode()
 (a.output/'device-test.txt').write_text(result)
 assert 'OK (1 test)' in result,result
 print('Manual UI saved mapping; system hosts restored; ADB unrooted; app-UID HTTPS test passed.')
finally:
 device('uninstall','com.helix.runtime.cli.test')
open_settings()
