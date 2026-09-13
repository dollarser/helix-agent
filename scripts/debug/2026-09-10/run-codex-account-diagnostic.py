"""Explicit owned USB phone diagnostic: update Runtime in place, preserve its vault, remove only our test APK."""
import argparse, hashlib, json, os, re, subprocess
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--output',required=True,type=Path)
p.add_argument('--method', choices=['highestDeclaredEffortContract', 'productionRequestContract'])
a=p.parse_args();assert not a.serial.startswith('emulator-')
adb=Path(os.environ['ANDROID_HOME'])/'platform-tools/adb'
def call(*args,timeout=180):
 r=subprocess.run([str(adb),'-s',a.serial,*args],capture_output=True,text=True,timeout=timeout)
 if r.returncode:raise RuntimeError(r.stdout+r.stderr)
 return r.stdout+r.stderr
assert call('get-state').strip()=='device'
assert call('shell','pm','path','com.helix.runtime.cli').strip()
assert 'package:com.helix.runtime.cli.test' not in call('shell','pm','list','packages','com.helix.runtime.cli').splitlines()
a.output.mkdir(parents=True,exist_ok=False)
artifacts=[Path('runtime/cli-app/build/outputs/apk/debug/cli-app-debug.apk'),Path('runtime/cli-app/build/outputs/apk/androidTest/debug/cli-app-debug-androidTest.apk')]
(a.output/'artifacts.json').write_text(json.dumps([{'path':str(f),'sha256':hashlib.sha256(f.read_bytes()).hexdigest()} for f in artifacts],indent=2))
owned=False
try:
 for f in artifacts:
  result=call('install','-r',str(f));assert 'Success' in result,result
  if f==artifacts[-1]:owned=True
 try:
  selected='com.helix.runtime.cli.app.CodexRealAccountDiagnosticTest'+('#'+a.method if a.method else '')
  raw=call('shell','am','instrument','-w','-r','-e','class',selected,'-e','realCodex','true','com.helix.runtime.cli.test/androidx.test.runner.AndroidJUnitRunner',timeout=600)
 except subprocess.TimeoutExpired as failure:
  (a.output/'instrumentation-timeout.txt').write_bytes(failure.stdout or b'')
  raise
 (a.output/'instrumentation.txt').write_text(raw)
 print(raw)
 assert re.search(r'^OK \([1-9][0-9]* tests?\)',raw,re.M), 'Diagnostic did not pass a non-empty test selection'
 assert not any(x in raw for x in ['FAILURES!!!','INSTRUMENTATION_FAILED','Process crashed']), 'Diagnostic failed'
finally:
 if owned:(a.output/'cleanup.txt').write_text(call('uninstall','com.helix.runtime.cli.test'))
