"""Read error classes only for recent subscription results; never emit prompts, responses or credentials."""
import json, subprocess, os
from pathlib import Path
adb=Path(os.environ['ANDROID_HOME'])/'platform-tools/adb'
serial=os.environ['HELIX_PHONE_SERIAL']
assert not serial.startswith('emulator-')
def read(pkg,path):
 return subprocess.check_output([str(adb),'-s',serial,'shell','run-as',pkg,'cat',path],stderr=subprocess.DEVNULL)
names=subprocess.check_output([str(adb),'-s',serial,'shell','run-as','com.helix.runtime.cli','ls','-t','files/provider-v1/codex-model-jobs'],text=True).splitlines()[:8]
for job in names:
 record=json.loads(read('com.helix.runtime.cli',f'files/provider-v1/codex-model-jobs/{job}/record.json'))
 info={'job':job,'state':record['state'],'durationMs':record.get('terminalAtEpochMillis',record['createdAtEpochMillis'])-record['createdAtEpochMillis']}
 try:
  body=json.loads(read('com.helix.agent.developer',f'files/workspaces/app/.helix/subscription-results/{job}.json'))
  info['errors']=[{k:e[k] for k in ('type','code','retryable') if k in e} for e in body['events'] if e.get('type')=='error']
  info['eventCount']=len(body['events'])
 except subprocess.CalledProcessError: info['localResult']='absent'
 print(json.dumps(info))
