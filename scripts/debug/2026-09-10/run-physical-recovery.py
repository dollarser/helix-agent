"""Fresh consumer sandbox on an explicitly selected phone; preserve the installed developer app."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--output',required=True,type=Path)
p.add_argument('--test', default='com.helix.app.ui.PhysicalBackgroundRecoveryDeviceTest');p.add_argument('--host-timeout', type=int, default=420)
a=p.parse_args();assert not a.serial.startswith('emulator-')
adb=str(Path(os.environ['ANDROID_HOME'])/'platform-tools/adb');out=a.output;out.mkdir(parents=True,exist_ok=False)
def device(*args,timeout=900):
 r=subprocess.run([adb,'-s',a.serial,*args],text=True,capture_output=True,timeout=timeout)
 if r.returncode:raise RuntimeError(r.stdout+r.stderr)
 return r.stdout+r.stderr
assert device('shell','getprop','ro.kernel.qemu').strip()!='1'
installed=set(device('shell','pm','list','packages','com.helix').splitlines())
assert 'package:com.helix.agent' not in installed and 'package:com.helix.agent.test' not in installed
artifacts=[('com.helix.agent','app/build/outputs/apk/consumer/debug/app-consumer-debug.apk'),('com.helix.agent.test','app/build/outputs/apk/androidTest/consumer/debug/app-consumer-debug-androidTest.apk')]
classes=[a.test]
assert a.test.startswith('com.helix.app.') and all(c.isalnum() or c in '._#$' for c in a.test)
added=[];cleanup=[];result={'classes':classes,'artifacts':[]}
try:
 for pkg,path in artifacts:
  apk=out/(pkg+'.apk');shutil.copy2(path,apk);actual=hashlib.sha256(apk.read_bytes()).hexdigest()
  result['artifacts'].append({'package':pkg,'sha256':actual});added.append(pkg)
  log=device('install','-r',str(apk),timeout=120);assert 'Success' in log,log
 with (out/'instrumentation.log').open('w') as log:
  proc=subprocess.Popen([adb,'-s',a.serial,'shell','am','instrument','-w','-r','-e','class',','.join(classes),'-e','helix.physical.recovery','true','com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner'],stdout=log,stderr=subprocess.STDOUT)
  try:proc.wait(timeout=a.host_timeout)
  except subprocess.TimeoutExpired:
   result['hostTimeoutSeconds']=a.host_timeout
   device('shell','am','force-stop','com.helix.agent');proc.wait(timeout=30)
 raw=(out/'instrumentation.log').read_text()
 (out/'instrumentation.log').write_text(raw)
 result['passed']=len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: 0\s*$',raw,re.M))
 result['assumptions']=len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: -4\s*$',raw,re.M))
 result['state']='PASS' if re.search(r'^OK \([1-9][0-9]* tests?\)',raw,re.M) and result['passed']>0 and not any(x in raw for x in ['FAILURES!!!','INSTRUMENTATION_FAILED','Process crashed']) else 'FAIL'
except Exception as failure:result['state']='ERROR';result['error']=str(failure)
finally:
 for pkg in reversed(added):
  try:cleanup.append({'package':pkg,'uninstall':device('uninstall',pkg,timeout=120).strip()})
  except Exception as failure:cleanup.append({'package':pkg,'error':str(failure)})
 result['cleanup']=cleanup;(out/'results.json').write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2),flush=True)

raise SystemExit(0 if result.get("state")=="PASS" and all("Success" in c.get("uninstall", "") for c in cleanup) else 1)
