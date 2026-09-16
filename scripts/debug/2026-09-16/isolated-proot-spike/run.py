#!/usr/bin/env python3
"""Run the opt-in isolated Service probe on a newly owned emulator only."""
import os, pathlib, shlex, subprocess, time
ROOT=pathlib.Path(__file__).resolve().parents[4]
SDK=pathlib.Path(os.environ.get('ANDROID_HOME', str(pathlib.Path.home()/'Library/Android/sdk')))
OUT=ROOT/'build/isolated-proot-spike'
AVD=os.environ.get('POC_AVD','Helix_API_29')
SERIAL='emulator-5584'
adb=[str(SDK/'platform-tools/adb'),'-s',SERIAL]
def run(args, timeout=120):
    p=subprocess.run(args,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=timeout)
    print(p.stdout,end='',flush=True);p.check_returncode();return p.stdout
def shell(args): return run(adb+['shell',shlex.join(args)])
if SERIAL in run([str(SDK/'platform-tools/adb'),'devices']): raise RuntimeError('serial already owned')
OUT.mkdir(parents=True,exist_ok=True)
with open(OUT/(AVD+'-emulator.log'),'w') as log:
    p=subprocess.Popen([str(SDK/'emulator/emulator'),'-avd',AVD,'-port','5584','-read-only','-no-snapshot','-no-window','-no-audio'],stdout=log,stderr=subprocess.STDOUT)
    try:
        for _ in range(180):
            if p.poll() is not None: raise RuntimeError('emulator exited')
            b=subprocess.run(adb+['shell','getprop','sys.boot_completed'],text=True,capture_output=True,timeout=10)
            if b.stdout.strip()=='1': break
            time.sleep(1)
        else: raise RuntimeError('boot timeout')
        run(adb+['install','-r',str(ROOT/'app/build/outputs/apk/developer/debug/app-developer-debug.apk')],180)
        run(adb+['install','-r',str(ROOT/'app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk')],180)
        package='com.helix.agent.developer'
        result=shell(['am','instrument','-w','-r','-e','isolatedProotProbe','true','-e','class','com.helix.app.proot.IsolatedProotFeasibilityDeviceTest',package+'.test/com.helix.app.HelixAndroidJUnitRunner'])
        (OUT/(AVD+'-logcat.txt')).write_text(shell(['logcat','-d','-t','3000']))
        report=shell(['run-as',package,'cat','files/isolated-proot-probe/report.txt'])
        (OUT/(AVD+'-report.txt')).write_text(report)
        (OUT/(AVD+'-logcat.txt')).write_text(shell(['logcat','-d','-t','1500']))
        assert 'OK (1 test)' in result, result
    finally:
        p.terminate()
        try:p.wait(timeout=30)
        except subprocess.TimeoutExpired:p.kill();p.wait(timeout=10)
