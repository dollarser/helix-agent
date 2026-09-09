#!/usr/bin/env python3
"""Run affected library suites and Accessibility recovery only on the parent-owned emulator."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

serial, directory = sys.argv[1:]
output = Path(directory)
owner = json.loads((output / 'owner.json').read_text())
assert owner['serial'] == serial
adb = str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb')
results=[]

def device(*args, timeout=900):
    os.kill(owner['pid'], 0)
    result=subprocess.run([adb,'-s',serial,*args],text=True,capture_output=True,timeout=timeout,check=True)
    return result.stdout+result.stderr

def suite(name, runner, classes, extras=()):
    text=device('shell','am','instrument','-w','-r','-e','class',classes,*extras,runner)
    (output/(name+'.txt')).write_text(text)
    match=re.search(r'^OK \(([1-9][0-9]*) tests?\)',text,re.M)
    assert match and not any(x in text for x in ['FAILURES!!!','INSTRUMENTATION_FAILED','Process crashed']),name
    skipped=text.count('INSTRUMENTATION_STATUS_CODE: -4')
    assert 'INSTRUMENTATION_STATUS_CODE: -3' not in text, 'Unexpected ignored test'
    assert int(match[1]) == text.count('INSTRUMENTATION_STATUS_CODE: 0') + skipped
    results.append({'suite':name,'reportedTests':int(match[1]),'assumptions':skipped,
                    'completed':text.count('INSTRUMENTATION_STATUS_CODE: 0')})
    (output/'module-results.json').write_text(json.dumps(results,indent=2))
    print(name,match[0],'assumptions',skipped,flush=True)

artifacts={}
for module in ['core/storage','feature/files','feature/browser','runtime/quickjs','tools/android','tools/automation']:
    folder=Path(module)/'build/outputs/apk/androidTest/debug'
    meta=json.loads((folder/'output-metadata.json').read_text())
    apk=folder/meta['elements'][0]['outputFile']
    digest=hashlib.sha256(apk.read_bytes()).hexdigest()
    artifacts[str(apk)]=digest
    (output/'module-artifacts.json').write_text(json.dumps(artifacts,indent=2))
    device('install','-r',str(apk),timeout=120)
    classes=[]
    for p in sorted((Path(module)/'src/androidTest').rglob('*Test.kt')):
        if p.stem=='AutomationForceStopDeviceTest':continue
        source=p.read_text()
        package=re.search(r'^package (\S+)',source,re.M)[1]
        classes.append(package+'.'+p.stem)
    assert classes,module
    runner=meta['applicationId']+'/androidx.test.runner.AndroidJUnitRunner'
    suite(module.replace('/','-'),runner,','.join(classes))
    if module=='tools/automation':
        setup_path=output/'automation-force-stop-setup.txt'
        with setup_path.open('w') as log:
            process=subprocess.Popen([adb,'-s',serial,'shell','am','instrument','-w','-r',
                '-e','class','com.helix.tools.automation.AutomationForceStopSetupDeviceTest',
                '-e','hxa093ForceStopPhase','setup',runner],stdout=log,stderr=subprocess.STDOUT,text=True)
            try:
                deadline=time.monotonic()+50
                while time.monotonic()<deadline:
                    if 'hxa093ReadyForHostKill=1' in setup_path.read_text():break
                    if process.poll() is not None:raise RuntimeError('Setup exited without a live session')
                    time.sleep(0.5)
                else:raise TimeoutError('Accessibility live session readiness')
                before=device('shell','pidof',meta['applicationId']).strip()
                assert before,'No live target process before host force-stop'
                stopped=device('shell','am','force-stop',meta['applicationId'])
                (output/'automation-force-stop.txt').write_text('live pid='+before+'\n'+stopped)
                process.wait(timeout=20)
            finally:
                if process.poll() is None:process.terminate();process.wait(timeout=10)
        suite('automation-force-stop-recovery',runner,
              'com.helix.tools.automation.AutomationForceStopRecoveryDeviceTest',
              ('-e','hxa093ForceStopPhase','recovery'))
    assert hashlib.sha256(apk.read_bytes()).hexdigest()==digest,'APK changed during run'
