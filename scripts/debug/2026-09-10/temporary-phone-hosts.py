"""Apply/revert a reboot-ephemeral chatgpt.com hosts bind mount on one authorized rooted phone."""
import argparse,os,shlex,socket,subprocess,json,uuid
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial',required=True);p.add_argument('--output',required=True,type=Path);p.add_argument('--restore',action='store_true');a=p.parse_args()
assert not a.serial.startswith('emulator-')
adb=str(Path(os.environ['ANDROID_HOME'])/'platform-tools/adb')
def device(args,**kwargs):
 return subprocess.run([adb,'-s',a.serial,*args],check=True,capture_output=True,timeout=25,**kwargs).stdout
def shell(*args):return device(['shell',shlex.join(args)],text=True).strip()
assert shell('id','-u')=='0', 'ADB root required'
target='/system/etc/hosts'
record=a.output/'mapping.json'
if a.restore:
 d=json.loads(record.read_text());assert d['serial']==a.serial
 mounts=shell('cat','/proc/self/mountinfo')
 if any(line.split()[4]==target for line in mounts.splitlines()):
  assert device(['shell','cat',target])==(a.output/'hosts.temporary').read_bytes(), 'Hosts changed; do not remove another mapping'
  shell('umount',target)
 shell('rm','-f',d['temporaryFile'])
 shell('am','force-stop','com.helix.runtime.cli')
 print('Temporary hosts mapping removed; subscription DNS cache reset.')
 raise SystemExit()
a.output.mkdir(parents=True,exist_ok=False)
assert not any(line.split()[4]==target for line in shell('cat','/proc/self/mountinfo').splitlines()), 'Existing hosts mount must not be replaced'
original=device(['shell','cat',target])
(a.output/'hosts.original').write_bytes(original)
ips=sorted({r[4][0] for r in socket.getaddrinfo('chatgpt.com',443,socket.AF_INET)})
assert ips
# Fail rather than override a pre-existing explicit mapping.
assert not any('chatgpt.com' in l.split('#')[0].split()[1:] for l in original.decode().splitlines())
body=original+b'\n# Helix temporary DNS workaround; bind mount disappears on reboot.\n'+''.join(ip+' chatgpt.com\n' for ip in ips).encode()
(a.output/'hosts.temporary').write_bytes(body)
tmp='/data/local/tmp/helix-hosts-'+uuid.uuid4().hex
record.write_text(json.dumps({'serial':a.serial,'host':'chatgpt.com','addresses':ips,'temporaryFile':tmp,'target':target},indent=2))
mounted=False
try:
 device(['push',str(a.output/'hosts.temporary'),tmp])
 shell('chmod','644',tmp);shell('chown','0:0',tmp)
 shell('chcon','u:object_r:system_file:s0',tmp)
 shell('mount','--bind',tmp,target);mounted=True
 assert device(['shell','cat',target])==body
 shell('am','force-stop','com.helix.runtime.cli')
 result=shell('run-as','com.helix.runtime.cli','/system/bin/curl','-q','--noproxy','*','--connect-timeout','5','--max-time','10','-sS','-o','/dev/null','-w','http=%{http_code} remote=%{remote_ip} tls=%{time_appconnect}','https://chatgpt.com/backend-api/codex/models')
 assert 'http=401' in result,result
 (a.output/'verification.txt').write_text(result+'\n')
 print('Temporary mapping active: '+', '.join(ips));print(result)
 shell('am','start','-W','-n','com.helix.runtime.cli/com.helix.runtime.cli.app.CodexLoginActivity')
except BaseException:
 if mounted:shell('umount',target)
 shell('rm','-f',tmp)
 raise
