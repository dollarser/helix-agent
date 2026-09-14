"""Credential-free HTTPS checks from the explicitly selected subscription UID; no model calls."""
import subprocess,json,concurrent.futures,xml.etree.ElementTree as ET
import os,shlex
from pathlib import Path
ADB=str(Path(os.environ['ANDROID_HOME'])/'platform-tools/adb')
SERIAL=os.environ['HELIX_PHONE_SERIAL']
assert not SERIAL.startswith('emulator-')
def run(args):
 if args[0]=='shell': args=['shell', shlex.join(args[1:])]
 r=subprocess.run([ADB,'-s',SERIAL,*args],capture_output=True,text=True,timeout=25)
 return r

def check(url):
 r=run(['shell','run-as','com.helix.runtime.cli','/system/bin/curl','-q','--connect-timeout','8','--max-time','12','-sS','-o','/dev/null','-w','http=%{http_code} dns=%{time_namelookup} connect=%{time_connect} tls=%{time_appconnect}',url])
 return {'url':url,'rc':r.returncode,'result':r.stdout,'error':r.stderr}
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
 for result in pool.map(check,['https://chatgpt.com/backend-api/codex/models','https://auth.openai.com/']): print(json.dumps(result))
r=run(['shell','run-as','com.helix.runtime.cli','ls','-t','files/codex-model-jobs'])
for job in r.stdout.splitlines()[:3]:
 if not job.startswith('job_'): continue
 q=run(['shell','run-as','com.helix.runtime.cli','cat',f'files/codex-model-jobs/{job}/record.json'])
 d=json.loads(q.stdout)
 print(json.dumps({k:d.get(k) for k in ['jobId','state','createdAtEpochMillis','terminalAtEpochMillis']}))
r=run(['shell','uiautomator','dump','/dev/tty'])
a=r.stdout.find('<?xml'); b=r.stdout.rfind('</hierarchy>')
if a>=0 and b>=0:
 root=ET.fromstring(r.stdout[a:b+len('</hierarchy>')])
 for n in root.iter('node'):
  t=n.get('text','')
  if any(k in t for k in ['失败','failed','网络','HTTP','已登录','Logged in']): print('visible_status='+t)
