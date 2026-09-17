"""Owned baseline regression instrumentation; external profiles remain opt-in."""
import datetime,json,os,re,subprocess,time,sys
from pathlib import Path
root=Path(__file__).resolve().parents[3]
sdk=Path(os.environ['ANDROID_HOME']);adb=str(sdk/'platform-tools/adb')
out=root/'build'/('pre-hxa-device-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S'));out.mkdir()
print(out.relative_to(root),flush=True)
def run(args,**kw):return subprocess.run(args,cwd=root,check=True,**kw)
def devices():return run([adb,'devices'],capture_output=True,text=True).stdout.strip().splitlines()[1:]
results=[]
try:
 for api,port in ([(29,5574)] if '--api29' in sys.argv else [(36,5576)] if '--api36' in sys.argv else [(29,5574),(36,5576)]):
  serial=f'emulator-{port}'
  if any(row.split()[0]==serial for row in devices()):raise RuntimeError('Refuse existing owned-port serial')
  with (out/f'emulator-{api}.log').open('w') as log:
   child=subprocess.Popen([str(sdk/'emulator/emulator'),'-avd',(sys.argv[sys.argv.index('--avd')+1] if '--avd' in sys.argv else f'Helix_API_{api}'),'-port',str(port),'-read-only','-no-window','-no-audio','-no-snapshot','-memory','2048','-cores','2','-gpu','swiftshader_indirect'],stdout=log,stderr=subprocess.STDOUT)
   try:
    deadline=time.monotonic()+300
    while time.monotonic()<deadline:
     if child.poll() is not None:raise RuntimeError('Owned emulator exited')
     state=subprocess.run([adb,'-s',serial,'shell','getprop','sys.boot_completed'],capture_output=True,text=True)
     if state.stdout.strip()=='1':break
     time.sleep(2)
    else:raise RuntimeError('Boot timeout')
    for cmd in [('size','1080x2400'),('density','420')]:run([adb,'-s',serial,'shell','wm',*cmd],capture_output=True)
    for flavor in (['Consumer'] if '--consumer' in sys.argv else ['Developer'] if '--developer' in sys.argv else ['Consumer','Developer']):
     package='com.helix.agent'+('.developer' if flavor=='Developer' else '')
     for pkg in [package+'.test',package]:subprocess.run([adb,'-s',serial,'uninstall',pkg],capture_output=True)
     with (out/f'{api}-{flavor}-assemble.log').open('w') as log:
      run(['./gradlew',f':app:assemble{flavor}Debug',f':app:assemble{flavor}DebugAndroidTest','--console=plain'],stdout=log,stderr=subprocess.STDOUT)
     for apk in [root/f'app/build/outputs/apk/{flavor.lower()}/debug/app-{flavor.lower()}-debug.apk',root/f'app/build/outputs/apk/androidTest/{flavor.lower()}/debug/app-{flavor.lower()}-debug-androidTest.apk']:
      run([adb,'-s',serial,'install','-r',str(apk)],capture_output=True)
     normal='com.helix.app.ToolApprovalSettingsDeviceTest,com.helix.app.ToolApprovalSettingsLifecycleDeviceTest,com.helix.app.ui.ApprovalCardScreenTest,com.helix.app.ToolApprovalPreferenceDeviceTest,com.helix.app.ToolSchedulerDeviceTest,com.helix.app.ToolPreferenceStopDeviceTest,com.helix.app.ApprovalFlowDeviceTest'
     phases=[('normal',normal,52,1),('restart','com.helix.app.ToolApprovalSettingsDeviceTest',16,0)]
     if '--full' in sys.argv:phases=[('full',None,None,None)]
     if '--class' in sys.argv:phases=[('selected',sys.argv[sys.argv.index('--class')+1],None,int(sys.argv[sys.argv.index('--expected-skips')+1]) if '--expected-skips' in sys.argv else 0)]
     storage_granted='com.helix.app.ui.ManualSharedFileDeviceTest,com.helix.app.ui.SharedStorageDeviceTest#grantedRootNavigationKeepsAgentScopeSeparate,com.helix.app.ui.SharedStorageDeviceTest#revokingAppOpRemovesTheManualRootWithoutGrantingAgentAccess'
     storage_revoked='com.helix.app.ui.SharedStorageDeviceTest#revokingAppOpRemovesTheManualRootWithoutGrantingAgentAccess'
     if api>=30 and ('--full' in sys.argv or '--storage-only' in sys.argv) and '--class' not in sys.argv:
      if '--storage-only' in sys.argv:phases=[]
      phases += [('storage-granted',storage_granted,3,0),('storage-revoked',storage_revoked,1,0)]
     for phase,selected,expected,expected_skips in phases:
      run([adb,'-s',serial,'shell','am','force-stop',package],capture_output=True)
      cmd=[adb,'-s',serial,'shell','am','instrument','-w','-r','-e','hxa201SettingsPhase',phase]
      if api>=30 and phase=='full':cmd+=['-e','notAnnotation','com.helix.app.ui.RequiresStorageHostPhase']
      if phase.startswith('storage-'):
       grant=phase=='storage-granted'
       run([adb,'-s',serial,'shell','appops','set',package,'MANAGE_EXTERNAL_STORAGE','allow' if grant else 'ignore'],capture_output=True)
       cmd+=['-e','hxaStoragePhase','granted' if grant else 'revoked']
      if selected:cmd+=['-e','class',selected]
      cmd+=[package+'.test/com.helix.app.HelixAndroidJUnitRunner']
      with (out/f'{api}-{flavor}-{phase}-live-logcat.txt').open('w') as trace_log:
       trace=subprocess.Popen([adb,'-s',serial,'logcat','-v','threadtime'],stdout=trace_log,stderr=subprocess.STDOUT)
       try:result=subprocess.run(cmd,cwd=root,capture_output=True,text=True,timeout=2400)
       finally:
        trace.terminate()
        try:trace.wait(timeout=5)
        except subprocess.TimeoutExpired:trace.kill();trace.wait(timeout=5)
      raw=result.stdout+result.stderr;(out/f'{api}-{flavor}-{phase}.txt').write_text(raw)
      codes=[int(x) for x in re.findall(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)',raw)]
      passed=codes.count(0);skips=codes.count(-3)+codes.count(-4);failures=sum(x not in [0,1,-3,-4] for x in codes)
      row=dict(api=api,flavor=flavor,phase=phase,passed=passed,skipped=skips,failed=failures,exit=result.returncode)
      results.append(row);print(json.dumps(row),flush=True)
      with (out/f'{api}-{flavor}-{phase}-logcat.txt').open('w') as log:run([adb,'-s',serial,'logcat','-d'],stdout=log)
      captures=subprocess.run([adb,'-s',serial,'shell','run-as',package,'ls','files/hxa201-captures'],capture_output=True,text=True)
      if captures.returncode==0:
       for name in captures.stdout.splitlines():
        if not re.fullmatch(r'[a-zA-Z0-9_.-]+\.png',name):continue
        data=run([adb,'-s',serial,'exec-out','run-as',package,'cat','files/hxa201-captures/'+name],capture_output=True).stdout
        (out/f'{api}-{flavor}-{phase}-{name}').write_bytes(data)
      if result.returncode or failures or (expected is not None and passed!=expected) or (expected_skips is not None and skips!=expected_skips) or passed==0 or 'INSTRUMENTATION_CODE: -1' not in raw:
       raise RuntimeError('Device matrix failed: see raw status and logcat')
     if '--goal-kill' in sys.argv:
      run([sys.executable,'scripts/run-goal-process-kill.py','--serial',serial,'--adb',adb,'--package',package,'--output',str(out/f'{api}-{flavor}-goal-kill')])
   finally:
    child.terminate()
    try:child.wait(timeout=20)
    except subprocess.TimeoutExpired:child.kill();child.wait(timeout=10)
    (out/f'owned-exit-{api}.txt').write_text(str(child.returncode))
    for _ in range(30):
     if not any(row.split()[0]==serial for row in devices()):break
     time.sleep(1)
finally:
 (out/'summary.json').write_text(json.dumps(results,indent=2))
 (out/'adb-final.txt').write_text(run([adb,'devices'],capture_output=True,text=True).stdout)
