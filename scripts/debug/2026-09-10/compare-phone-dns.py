"""Compare credential-free DNS/TCP/TLS from phone app UID and host without changing network settings."""
import os,shlex,subprocess,concurrent.futures,json,socket
from pathlib import Path
adb=str(Path(os.environ['ANDROID_HOME'])/'platform-tools/adb')
serial=os.environ['HELIX_PHONE_SERIAL']; assert not serial.startswith('emulator-')
def phone(args):
 r=subprocess.run([adb,'-s',serial,'shell',shlex.join(args)],capture_output=True,text=True,timeout=20)
 return {'rc':r.returncode,'out':r.stdout.strip(),'err':r.stderr.strip()}
def probe(item):
 label,args=item
 return {'label':label,**phone(args)}
base=['run-as','com.helix.runtime.cli','/system/bin/curl','-q','-sS','-o','/dev/null','--connect-timeout','5','--max-time','8','-w','http=%{http_code} remote=%{remote_ip} dns=%{time_namelookup} tcp=%{time_connect} tls=%{time_appconnect}']
queries=[('phone-default',base+['https://chatgpt.com/backend-api/codex/models']),('phone-ipv4',base+['-4','https://chatgpt.com/backend-api/codex/models']),('phone-ipv6',base+['-6','https://chatgpt.com/backend-api/codex/models']),('phone-dns', ['ping','-c','1','-W','1','chatgpt.com']),('private-dns-mode',['settings','get','global','private_dns_mode']),('private-dns-host',['settings','get','global','private_dns_specifier']),('system-proxy',['settings','get','global','http_proxy'])]
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
 for x in pool.map(probe,queries): print(json.dumps(x),flush=True)
print('host-resolved='+json.dumps(sorted({r[4][0] for r in socket.getaddrinfo('chatgpt.com',443)})))
for label,cmd in [('host-system-proxy',['scutil','--proxy']),('host-direct',['curl','-q','--noproxy','*','--connect-timeout','5','--max-time','8','-sS','-o','/dev/null','-w','http=%{http_code} remote=%{remote_ip} tcp=%{time_connect} tls=%{time_appconnect}','https://chatgpt.com/backend-api/codex/models'])]:
 r=subprocess.run(cmd,capture_output=True,text=True,timeout=15); print(json.dumps({'label':label,'rc':r.returncode,'out':r.stdout,'err':r.stderr}))
