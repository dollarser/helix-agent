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
a=p.parse_args();assert not a.serial.startswith('emulator-')
adb=str(Path(os.environ['ANDROID_HOME'])/'platform-tools/adb');out=a.output;out.mkdir(parents=True,exist_ok=False)
def device(*args,timeout=900):
 r=subprocess.run([adb,'-s',a.serial,*args],text=True,capture_output=True,timeout=timeout)
 if r.returncode:raise RuntimeError(r.stdout+r.stderr)
 return r.stdout+r.stderr
assert device('shell','getprop','ro.kernel.qemu').strip()!='1'
installed=set(device('shell','pm','list','packages','com.helix').splitlines())
assert 'package:com.helix.agent' not in installed and 'package:com.helix.agent.test' not in installed
artifacts=[('com.helix.agent','app/build/outputs/apk/consumer/debug/app-consumer-debug.apk','8d111db1cc203b56f83b0621470653f2f0e4ad10b0fb13c1f9d1f85302937d45'),('com.helix.agent.test','app/build/outputs/apk/androidTest/consumer/debug/app-consumer-debug-androidTest.apk','0e5147938b073f8983d7da34028b1be3060c953de84d2655bb884c714a42d411')]
classes=['com.helix.app.chat.GoalModelReportDeviceTest','com.helix.app.chat.ContextCompactionDeviceTest','com.helix.app.chat.LongTurnCompactionDeviceTest','com.helix.app.chat.BackgroundTaskStorageDeviceTest','com.helix.app.chat.ChatSessionLifecycleDeviceTest','com.helix.app.ui.ConversationHeaderDeviceTest','com.helix.app.ui.ConversationTopBarDeviceTest','com.helix.app.ui.FilesHomeDeviceTest']
added=[];cleanup=[];result={'classes':classes,'artifacts':[]}
try:
 for pkg,path,digest in artifacts:
  apk=out/(pkg+'.apk');shutil.copy2(path,apk);actual=hashlib.sha256(apk.read_bytes()).hexdigest();assert actual==digest,path
  result['artifacts'].append({'package':pkg,'sha256':actual});added.append(pkg)
  log=device('install','-r',str(apk),timeout=120);assert 'Success' in log,log
 raw=device('shell','am','instrument','-w','-r','-e','class',','.join(classes),'com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner')
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
