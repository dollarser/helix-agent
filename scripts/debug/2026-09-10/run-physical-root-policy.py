"""Stateful, explicitly owned test-UID policy check; never edits the Magisk database."""
import argparse,hashlib,json,os,re,shutil,subprocess
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--stage',choices=['setup','granted','denied','cleanup'],required=True);a=p.parse_args();assert not a.serial.startswith('emulator-')
adb=str(Path(os.environ['ANDROID_HOME'])/'platform-tools/adb');pkg='com.helix.tools.root.test';out=a.output

def device(*args):
 r=subprocess.run([adb,'-s',a.serial,*args],capture_output=True,text=True,timeout=90)
 if r.returncode:raise RuntimeError(r.stdout+r.stderr)
 return r.stdout+r.stderr
assert device('shell','getprop','ro.kernel.qemu').strip()!='1'
state=out/'ownership.json'
if a.stage=='setup':
 assert 'package:'+pkg not in device('shell','pm','list','packages',pkg).splitlines()
 out.mkdir(parents=True,exist_ok=False);apk=out/'root.apk';shutil.copy2('tools/root/build/outputs/apk/androidTest/debug/root-debug-androidTest.apk',apk)
 assert 'Success' in device('install',str(apk))
 uid=device('shell','pm','list','packages','-U',pkg).strip()
 state.write_text(json.dumps({'package':pkg,'uidLine':uid,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest()},indent=2))
else:
 ownership=json.loads(state.read_text());assert ownership['package']==pkg
 assert device('shell','pm','list','packages','-U',pkg).strip()==ownership['uidLine'],'Ownership changed'
 if a.stage=='cleanup':
  result={'uninstall':device('uninstall',pkg)}
 else:
  method='com.helix.tools.root.LibsuRootAccessDeviceTest#b_explicitRequestMatchesTheDeclaredDeviceProfile'
  raw=device('shell','am','instrument','-w','-r','-e','class',method,'-e','hxa094ExpectedRoot',a.stage,pkg+'/androidx.test.runner.AndroidJUnitRunner')
  (out/(a.stage+'.log')).write_text(raw)
  result={'state':'PASS' if re.search(r'OK \(1 test\)',raw) and 'FAILURES!!!' not in raw and 'Process crashed' not in raw else 'FAIL'}
 (out/(a.stage+'.json')).write_text(json.dumps(result,indent=2));print(result)
