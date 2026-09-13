"""Use host-resolved addresses for isolated phone HTTPS probes; never alter DNS or bypass TLS."""
import os,subprocess,shlex,socket,json,concurrent.futures
from pathlib import Path
adb=str(Path(os.environ['ANDROID_HOME'])/'platform-tools/adb'); serial=os.environ['HELIX_PHONE_SERIAL']; assert not serial.startswith('emulator-')
ips=sorted({r[4][0] for r in socket.getaddrinfo('chatgpt.com',443,socket.AF_INET)})
def probe(ip):
 args=['run-as','com.helix.runtime.cli','/system/bin/curl','-q','--noproxy','*','-sS','-o','/dev/null','--connect-timeout','5','--max-time','8','--resolve',f'chatgpt.com:443:{ip}','-w','http=%{http_code} remote=%{remote_ip} tcp=%{time_connect} tls=%{time_appconnect}','https://chatgpt.com/backend-api/codex/models']
 r=subprocess.run([adb,'-s',serial,'shell',shlex.join(args)],capture_output=True,text=True,timeout=15)
 return {'ip':ip,'rc':r.returncode,'out':r.stdout,'err':r.stderr}
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
 for r in pool.map(probe,ips):print(json.dumps(r),flush=True)
for server in ['192.168.99.1','223.5.5.5','119.29.29.29']:
 r=subprocess.run(['dig','+time=2','+tries=1','+short','@'+server,'chatgpt.com','A'],capture_output=True,text=True,timeout=5)
 print(json.dumps({'hostQueryServer':server,'rc':r.returncode,'answer':r.stdout.strip()}))
