"""Explicit local SGLang acceptance on a fresh, exclusively owned AVD."""
import argparse, datetime, hashlib, json, os, re, socket, subprocess, time, uuid
from pathlib import Path
parser=argparse.ArgumentParser();parser.add_argument('--api',choices=['29','36'],default='36');args=parser.parse_args()
root=Path(__file__).resolve().parents[3];sdk=Path(os.environ['ANDROID_HOME']);adb=str(sdk/'platform-tools/adb')
manager=str(sdk/'cmdline-tools/latest/bin/avdmanager');run_id=uuid.uuid4().hex[:10]
out=root/'build'/('sglang-closeout-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S')+'-'+args.api);out.mkdir()
name='Helix_sglang_'+run_id;package='com.helix.agent.developer';child=None;created=False;reserved=None
summary={'api':args.api,'head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),'runs':[]}
def run(cmd,**kw):return subprocess.run(cmd,cwd=root,check=True,**kw)
def devices():return run([adb,'devices'],capture_output=True,text=True).stdout
def instrument(label,clazz,extras,expected_pass,expected_skip):
 run([adb,'-s',serial,'shell','am','force-stop',package],capture_output=True)
 cmd=[adb,'-s',serial,'shell','am','instrument','-w','-r','-e','class',clazz,*extras,package+'.test/com.helix.app.HelixAndroidJUnitRunner']
 (out/(label+'-command.json')).write_text(json.dumps(cmd))
 start=time.monotonic();result=subprocess.run(cmd,cwd=root,capture_output=True,text=True,timeout=900)
 raw=result.stdout+result.stderr;(out/(label+'.txt')).write_text(raw)
 codes=[int(x) for x in re.findall(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)',raw)]
 row={'label':label,'passed':codes.count(0),'skipped':codes.count(-3)+codes.count(-4),'failed':sum(x not in [0,1,-3,-4] for x in codes),'seconds':round(time.monotonic()-start,2)}
 summary['runs'].append(row);print(json.dumps(row),flush=True)
 with (out/(label+'-logcat.txt')).open('w') as log:run([adb,'-s',serial,'logcat','-d','-v','threadtime'],stdout=log)
 if result.returncode or row['failed'] or row['skipped']!=expected_skip or (expected_pass is not None and row['passed']!=expected_pass) or (expected_pass is None and row['passed']==0) or 'INSTRUMENTATION_CODE: -1' not in raw:raise RuntimeError('Failed instrumentation; see '+str(out))
try:
 # Cooperative port reservation plus live serial and TCP checks. Never use existing devices.
 for port in range(5580,5680,2):
  serial=f'emulator-{port}';lock=root/'build'/f'owned-emulator-{port}.lock'
  try:fd=os.open(lock,os.O_CREAT|os.O_EXCL|os.O_WRONLY)
  except FileExistsError:continue
  os.close(fd)
  busy=serial in devices()
  for candidate in (port,port+1):
   with socket.socket() as probe:
    try:probe.bind(('127.0.0.1',candidate))
    except OSError:busy=True
  if busy:lock.unlink();continue
  reserved=lock;break
 else:raise RuntimeError('No exclusive emulator port')
 summary['serial']=serial;summary['devices_before']=devices()
 run([manager,'create','avd','--name',name,'--package',f'system-images;android-{args.api};google_apis;arm64-v8a','--device','pixel_5'],input='no\n',text=True,capture_output=True);created=True
 with (out/'emulator.log').open('w') as log:
  child=subprocess.Popen([str(sdk/'emulator/emulator'),'-avd',name,'-port',str(port),'-no-window','-no-audio','-no-snapshot','-memory','2048','-cores','2','-gpu','swiftshader_indirect'],stdout=log,stderr=subprocess.STDOUT)
  summary['owned_pid']=child.pid
  deadline=time.monotonic()+300
  while time.monotonic()<deadline:
   if child.poll() is not None:raise RuntimeError('Owned emulator exited')
   boot=subprocess.run([adb,'-s',serial,'shell','getprop','sys.boot_completed'],capture_output=True,text=True)
   if boot.stdout.strip()=='1':break
   time.sleep(2)
  else:raise RuntimeError('Boot timeout')
  for cmd in [('size','1080x2400'),('density','420')]:run([adb,'-s',serial,'shell','wm',*cmd],capture_output=True)
  for apk in [root/'app/build/outputs/apk/developer/debug/app-developer-debug.apk',root/'app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk']:
   summary[apk.name]=hashlib.sha256(apk.read_bytes()).hexdigest();run([adb,'-s',serial,'install',str(apk)],capture_output=True)
  clazz='com.helix.app.provider.SglangUiSmokeTest'
  instrument('default-opt-out',clazz,[],0,1)
  instrument('local-form-regression','com.helix.app.provider.ProviderModelDiscoveryUiTest',[],None,0)
  instrument('real-sglang',clazz,['-e','realSelfHosted','true'],1,0)
finally:
 if child is not None:
  child.terminate()
  try:child.wait(timeout=25)
  except subprocess.TimeoutExpired:child.kill();child.wait(timeout=10)
  summary['owned_exit']=child.returncode
 if created:run([manager,'delete','avd','--name',name],capture_output=True)
 if reserved:reserved.unlink(missing_ok=True)
 summary['devices_after']=devices();(out/'summary.json').write_text(json.dumps(summary,indent=2));print(out,flush=True)
