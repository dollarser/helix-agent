import os, subprocess, sys
from pathlib import Path
flavor=sys.argv[1]
out=f'build/r1-api36-{flavor}-{sys.argv[2]}'
classes=['ApprovalFlowDeviceTest','ExecutionEffectBoundaryDeviceTest','McpToolDiscoveryDeviceTest','SessionPermissionDeviceTest','SkillToolsDeviceTest','a2a.A2aDiscoveryDeviceTest','chat.NativeJavascriptDeviceTest','chat.GoalContinuationDeviceTest','chat.ChatSubmissionReceiptDeviceTest']
if len(sys.argv) > 3:
    classes = sys.argv[3].split(',')
env=os.environ.copy();env['ANDROID_HOME']=str(Path.home()/'Library/Android/sdk')
pkg='com.helix.agent'+('.developer' if flavor=='developer' else '')
cmd=[sys.executable,'scripts/run-owned-emulator.py','--avd','Helix_HXA229_Closeout_API36','--port','5560','--memory-mb','4096','--cores','2','--apk',f'app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk','--test-apk',f'app/build/outputs/apk/androidTest/{flavor}/debug/app-{flavor}-debug-androidTest.apk','--runner',pkg+'.test/com.helix.app.HelixAndroidJUnitRunner','--classes',','.join('com.helix.app.'+c for c in classes),'--output',out,'--timeout','1200','--raw-results','--clear-app-data']
if flavor == 'developer':
    cmd[cmd.index('--port')+1] = os.environ.get('HELIX_R1_PORT', '5562')
    cmd[cmd.index('--classes')+1] += ',com.helix.app.provider.SglangUiSmokeTest'
    cmd += ['--instrument-arg', 'realSelfHosted=true', '--after-script', 'scripts/debug/2026-09-28/run-p5-sglang-suites.py']
with open(out+'.log','w') as log: result=subprocess.run(cmd,env=env,stdout=log,stderr=subprocess.STDOUT)
print(out,result.returncode)
sys.exit(result.returncode)
