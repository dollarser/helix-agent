#!/usr/bin/env python3
"""HXA-241 fixed recovery cutpoints on the task's read-only emulator; not a generic runner."""
from pathlib import Path
import argparse
import importlib.util
import json
import os
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT/'scripts'))
from instrumentation_junit import parse, to_xml
spec = importlib.util.spec_from_file_location('hxa241_acceptance', Path(__file__).with_name('hxa241-emulator-acceptance.py'))
support = importlib.util.module_from_spec(spec)
spec.loader.exec_module(support)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--flavor',choices=['consumer','developer'],default='consumer')
    parser.add_argument('--storage',action='store_true')
    args=parser.parse_args()
    support.device_ready()
    package = 'com.helix.agent' + ('.developer' if args.flavor=='developer' else '')
    runner=package+'.test/com.helix.app.HelixAndroidJUnitRunner'
    out=support.OUT/('phases-'+args.flavor+'-'+time.strftime('%H%M%S'))
    out.mkdir(parents=True,exist_ok=False)
    packages={}
    for kind in ('app','test'):
        path=ROOT/(f'app/build/outputs/apk/{args.flavor}/debug/app-{args.flavor}-debug.apk' if kind=='app' else
                   f'app/build/outputs/apk/androidTest/{args.flavor}/debug/app-{args.flavor}-debug-androidTest.apk')
        packages[kind]={'sha256':support.digest(path),'bytes':path.stat().st_size}
        support.run([support.ADB,'-s',support.SERIAL,'install','-r','-t',path],log=out/f'install-{kind}.log')
    (out/'apk-identity.json').write_text(json.dumps(packages,indent=2)+'\n')
    records=[]

    def adb(*argv,timeout=180):
        support.device_ready()
        return support.run([support.ADB,'-s',support.SERIAL,*argv],timeout=timeout)

    def reset():
        if adb('shell','pm','clear',package).strip()!='Success':
            raise RuntimeError('Failed to reset this task emulator test installation')
        time.sleep(2)

    def instrument(selector, label, extras=(), crash=False):
        argv=['shell','am','instrument','-w','-r','-e','class',selector]
        for key,value in extras:
            argv += ['-e',key,value]
        raw=adb(*argv,runner)
        (out/(label+'.log')).write_text(raw)
        if crash:
            if 'Process crashed' not in raw or 'FAILURES!!!' in raw:
                raise RuntimeError('Setup did not reach required process death: '+label)
            return
        result=parse(raw)
        cls, _, method=selector.partition('#')
        if not result or any(c!=cls or (method and m!=method) or code!=0 for (c,m),(code,_) in result.items()):
            raise RuntimeError('Verification did not pass exact selector: '+label)
        (out/(label+'.xml')).write_text(to_xml(result))
        return list(c+'#'+m for c,m in result)

    cuts=[
        ('chat.MemoryProcessRecoveryDeviceTest','setup',None),
        ('localmodel.ModelPublicationRecoveryDeviceTest','setup',None),
        ('export.SessionExportRecoveryDeviceTest','setup',None),
    ]
    cuts += [('connector.ConnectorInstallRecoveryDeviceTest',c,'prepare') for c in ('preparing','before-commit','after-commit')]
    cuts += [('chat.WorkspaceProcessRecoveryDeviceTest',c,None) for c in ('setup','setup-before-rename','setup-purging','setup-purged')]
    cuts += [('files.WorkspaceBackupRecoveryDeviceTest',c,None) for c in ('setup-prepared','setup-deleted','setup-restoring')]
    if not args.storage:
        for suffix,cut,method in cuts:
            label=suffix+'-'+cut
            cls='com.helix.app.'+suffix
            try:
                reset()
                instrument(cls+('#'+method if method else ''), label+'-setup', [('recoveryPhase',cut)],crash=True)
                time.sleep(1)
                verified=instrument(cls+('#verify' if method else ''),label+'-verify',[('recoveryPhase','verify')])
                records.append({'case':label,'status':'passed','verified_methods':verified,'setup':'expected actual process death'})
            except Exception as error:
                records.append({'case':label,'status':'failed','error':str(error)})
            (out/'results.json').write_text(json.dumps(records,indent=2)+'\n')
            print(json.dumps(records[-1]),flush=True)
    else:
        reset()
        for suffix,method,phase in [
            ('ui.SharedStorageDeviceTest','grantedRootNavigationKeepsAgentScopeSeparate','granted'),
            ('ui.ManualSharedFileDeviceTest','userCanManageSharedFilesWithoutProviderAndDeleteRequiresConfirmation','granted'),
            ('ui.SharedStorageDeviceTest','revokingAppOpRemovesTheManualRootWithoutGrantingAgentAccess','granted'),
            ('ui.SharedStorageDeviceTest','revokingAppOpRemovesTheManualRootWithoutGrantingAgentAccess','revoked'),
        ]:
            label=suffix+'-'+method+'-'+phase
            try:
                adb('shell','appops','set',package,'MANAGE_EXTERNAL_STORAGE','allow' if phase=='granted' else 'ignore')
                time.sleep(2)
                verified=instrument('com.helix.app.'+suffix+'#'+method,label,[('hxaStoragePhase',phase)])
                records.append({'case':label,'status':'passed','verified_methods':verified})
            except Exception as error:
                records.append({'case':label,'status':'failed','error':str(error)})
            (out/'results.json').write_text(json.dumps(records,indent=2)+'\n')
            print(json.dumps(records[-1]),flush=True)
    print('RESULT_DIRECTORY='+str(out.relative_to(ROOT)),flush=True)
    if not records or any(r['status']!='passed' for r in records):
        raise RuntimeError('Recovery acceptance contains failures; see exact per-phase evidence')


if __name__=='__main__': main()
