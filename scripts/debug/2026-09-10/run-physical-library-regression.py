"""Explicit USB phone only; isolated library APKs, no installed Helix app data operations."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--output',required=True,type=Path)
p.add_argument('--modules', nargs='+', choices=['feature/browser','core/storage','feature/files','runtime/quickjs','tools/root','runtime/proot-ipc'], default=['feature/browser','core/storage','feature/files','runtime/quickjs'])
p.add_argument('--apk-metadata', type=Path, help='Explicit isolated build metadata, requires one module and SHA')
p.add_argument('--apk-sha256', help='Expected SHA for explicitly selected APK')
p.add_argument('--instrument-arg', nargs=2, action='append', default=[])
p.add_argument('--test', help='Fully qualified class or class#method; requires exactly one module')
p.add_argument('--headless', action='store_true', help='QuickJS-only freezer diagnostic, not normal acceptance')
p.add_argument('--host-timeout', type=int, default=300)
a=p.parse_args();assert not a.serial.startswith('emulator-')
assert not a.test or len(a.modules)==1
assert not a.apk_metadata or (len(a.modules)==1 and a.apk_sha256)
assert not a.headless or a.modules==['runtime/quickjs']
assert 20 <= a.host_timeout <= 300
adb=str(Path(os.environ['ANDROID_HOME'])/'platform-tools/adb')
out=a.output;out.mkdir(parents=True,exist_ok=False)
def device(*args,timeout=900):
    r=subprocess.run([adb,'-s',a.serial,*args],text=True,capture_output=True,timeout=timeout)
    if r.returncode:raise RuntimeError(r.stdout+r.stderr)
    return r.stdout+r.stderr
assert device('get-state').strip()=='device'
assert device('shell','getprop','ro.kernel.qemu').strip()!='1'
installed=set(device('shell','pm','list','packages','com.helix').splitlines())
old_autofill=device('shell','settings','get','secure','autofill_service').strip()
identity={k:device('shell','getprop',k).strip() for k in ['ro.product.manufacturer','ro.product.model','ro.build.version.sdk','ro.build.fingerprint','ro.product.cpu.abi']}
identity['pageSize']=device('shell','getconf','PAGESIZE').strip()
identity['webview']=device('shell','dumpsys','webviewupdate')
identity['gitCommit']=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
(out/'identity.json').write_text(json.dumps(identity,indent=2))
rows=[];added=[];cleanup=[]
try:
    for module in a.modules:
        row={'module':module,'headlessDiagnostic':a.headless,'hostDeadlineSeconds':a.host_timeout};rows.append(row)
        try:
            meta_path=a.apk_metadata or Path(module)/'build/outputs/apk/androidTest/debug/output-metadata.json'
            meta=json.loads(meta_path.read_text());pkg=meta['applicationId']
            assert pkg.startswith(('com.helix.feature.','com.helix.core.','com.helix.runtime.','com.helix.tools.')) and pkg.endswith('.test')
            assert 'package:'+pkg not in installed,'Existing test package preserved; use separately reviewed package'
            source=meta_path.parent/meta['elements'][0]['outputFile'];apk=out/(module.replace('/','-')+'.apk')
            shutil.copy2(source,apk);row['apkSha256']=hashlib.sha256(apk.read_bytes()).hexdigest();row['package']=pkg
            if a.apk_sha256:assert row['apkSha256']==a.apk_sha256
            if module=='feature/browser' and not a.apk_metadata:assert row['apkSha256']=='b9382e9768568bf71f6e4536b3485388e49e08e108d460a68b036f2c29583778'
            classes=[]
            for f in sorted((Path(module)/'src/androidTest').rglob('*Test.kt')):
                if f.stem in ['BrowserAutofillSoakDeviceTest','BrowserRealKeyboardInputDeviceTest']:continue
                classes.append(re.search(r'^package (\S+)',f.read_text(),re.M)[1]+'.'+f.stem)
            if a.test:
                assert a.test.split('#')[0] in classes, 'Test class must belong to selected module'
                classes=[a.test]
            row['classes']=classes
            print('START',module,flush=True)
            added.append(pkg)
            row['install']=device('install','-r',str(apk),timeout=120).strip()
            assert 'Success' in row['install'],row['install']
            log_path=out/(module.replace('/','-')+'.log')
            with log_path.open('w') as log:
                runner='androidx.test.runner.AndroidJUnitRunner'
                diagnostic=['-e','helix.quickjs.headless','true'] if a.headless else []
                diagnostic += [part for pair in a.instrument_arg for part in ('-e', *pair)]
                proc=subprocess.Popen([adb,'-s',a.serial,'shell','am','instrument','-w','-r','-e','class',','.join(classes),*diagnostic,pkg+'/'+runner],stdout=log,stderr=subprocess.STDOUT,text=True)
                try:proc.wait(timeout=a.host_timeout)
                except subprocess.TimeoutExpired:
                    row['hostTimeoutSeconds']=a.host_timeout
                    try:
                        pid=device('shell','pidof',pkg,timeout=5).strip()
                        if pid.isdigit():
                            state=device('shell','run-as',pkg,'sh','-c',f"'for t in /proc/{pid}/task/*; do echo $t; cat $t/comm $t/wchan; echo; done'",timeout=5)
                            (out/(module.replace('/','-')+'-timeout-threads.log')).write_text(state)
                    except Exception as failure:
                        row['timeoutDiagnosticError']=str(failure)
                    device('shell','am','force-stop',pkg,timeout=30)
                    proc.wait(timeout=30)
            raw=log_path.read_text()
            row['passed']=len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: 0\s*$',raw,re.M))
            row['assumptions']=len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: -4\s*$',raw,re.M))
            row['ignored']=len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: -3\s*$',raw,re.M))
            row['state']='PASS' if re.search(r'^OK \([1-9][0-9]* tests?\)',raw,re.M) and row['passed']>0 and not any(x in raw for x in ['FAILURES!!!','INSTRUMENTATION_FAILED','Process crashed']) else 'FAIL'
            print('END',module,row['state'],row['passed'],'passed',row['assumptions'],'assumptions',flush=True)
        except Exception as failure:
            row['state']='ERROR';row['error']=str(failure);print('ERROR',module,str(failure)[:300],flush=True)
        finally:
            (out/'results.json').write_text(json.dumps(rows,indent=2))
finally:
    try:
        now=device('shell','settings','get','secure','autofill_service').strip()
        if now!=old_autofill:
            assert now in ['null','', 'com.helix.feature.browser.test/com.helix.feature.browser.FixtureAutofillService'], 'Autofill changed externally; do not overwrite'
            if old_autofill in ['null','']:device('shell','settings','delete','secure','autofill_service')
            else:device('shell','settings','put','secure','autofill_service',old_autofill)
        cleanup.append({'autofillRestored':device('shell','settings','get','secure','autofill_service').strip()==old_autofill})
    except Exception as failure:cleanup.append({'autofillError':str(failure)})
    for pkg in added:
        try:cleanup.append({'package':pkg,'uninstall':device('uninstall',pkg,timeout=120).strip()})
        except Exception as failure:cleanup.append({'package':pkg,'error':str(failure)})
    (out/'cleanup.json').write_text(json.dumps(cleanup,indent=2))

raise SystemExit(0 if rows and all(r.get("state")=="PASS" for r in rows) and all(not any("error" in k.lower() for k in c) for c in cleanup) else 1)
