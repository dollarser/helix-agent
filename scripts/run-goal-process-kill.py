#!/usr/bin/env python3
"""Exercise the installed GoalProcessKillDeviceTest with two host SIGKILL boundaries."""
import argparse
from android_process_control import kill_emulator_app, verify_emulator_signal_control
import subprocess, pathlib, re, time, json, shutil

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--serial", required=True, help="Dedicated emulator serial; the app process will be killed")
parser.add_argument("--adb", default=shutil.which("adb"))
parser.add_argument("--output", type=pathlib.Path, help="Evidence directory; use a new directory to preserve prior runs")
args = parser.parse_args()
if not args.adb:
    parser.error("adb is not on PATH; pass --adb")
if not re.fullmatch(r"emulator-\d+", args.serial):
    parser.error("This non-physical-device verification requires an emulator serial")
root=args.output or (pathlib.Path(__file__).resolve().parents[1] / 'build/main-verification')
root.mkdir(parents=True, exist_ok=True)
adb=args.adb
base=[adb,'-s',args.serial]
verify_emulator_signal_control(base)
api=int(subprocess.check_output(base+['shell','getprop','ro.build.version.sdk'], text=True).strip())
cls='com.helix.app.chat.GoalProcessKillDeviceTest'
runner='com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner'
records=[]
for phase in ['control','prepare','recover-prepare','recover-final']:
    cmd=base+['shell','am','instrument','-w','-r','-e','class',cls,'-e','goal.kill.phase',phase,runner]
    path=root/f'goal-kill-{phase}.log'
    with path.open('w') as output:
        process=subprocess.Popen(cmd,stdout=output,stderr=subprocess.STDOUT)
        if phase in ['prepare','recover-prepare']:
            start=time.monotonic();match=None
            while time.monotonic()-start<25:
                match=re.search(r'GOAL_KILL_READY pid=(\d+)',path.read_text())
                if match or process.poll() is not None:break
                time.sleep(.05)
            if not match:
                process.terminate();process.wait(timeout=5)
                raise RuntimeError(f'{phase}: no durable ready marker; see {path}')
            ready_at=time.monotonic()
            pid=match.group(1)
            active=subprocess.check_output(base+['shell','pidof','com.helix.agent'],text=True).split()
            assert pid in active,(pid,active)
            kill_emulator_app(base, 'com.helix.agent', pid)
            process.wait(timeout=15)
            assert 'shortMsg=Process crashed.' in path.read_text(), path.read_text()
            records.append(dict(phase=phase,pid=int(pid),signal='SIGKILL',signalAuthority='emulator host su 0',readyToKillSeconds=time.monotonic()-ready_at,exit=process.returncode,log=path.name))
        else:
            process.wait(timeout=30)
            text=path.read_text()
            assert 'OK (1 test)' in text,text
            records.append(dict(phase=phase,tests=1,exit=process.returncode,log=path.name))
    print(records[-1],flush=True)
(root/'goal-kill-result.json').write_text(json.dumps(dict(serial=args.serial,api=api,records=records,scope='actual process SIGKILL of durable Goal time/model reservations; no Provider or tool backend execution'),indent=2)+'\n')
