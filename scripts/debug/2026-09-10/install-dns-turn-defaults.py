"""Cover-install latest apps, save visible DNS preset on the explicitly selected phone, verify settings only."""
from pathlib import Path
import os, subprocess, shlex, re, xml.etree.ElementTree as ET, json
adb=str(Path(os.environ['ANDROID_HOME'])/'platform-tools/adb');serial=os.environ['HELIX_PHONE_SERIAL'];assert not serial.startswith('emulator-')
def device(*args):return subprocess.check_output([adb,'-s',serial,*args],text=True,timeout=60)
def shell(*args):return device('shell',shlex.join(args))
def tree():
    path='/data/local/tmp/helix-default-dns.xml'
    shell('uiautomator','dump',path)
    try:return ET.fromstring(shell('cat',path))
    finally:shell('rm','-f',path)
def click(texts):
    for _ in range(3):
        for node in tree().iter('node'):
            if node.get('text') in texts:
                x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
                shell('input','tap',str((x1+x2)//2),str((y1+y2)//2));return
    raise RuntimeError('Settings control not visible; phone may need unlocking')
for apk in ['app/build/outputs/apk/developer/debug/app-developer-debug.apk','runtime/cli-app/build/outputs/apk/debug/cli-app-debug.apk']:
    print(device('install','-r',apk),flush=True)
shell('am','start','-W','-n','com.helix.runtime.cli/com.helix.runtime.cli.app.CliRuntimeHomeActivity')
click(['网络设置 · 域名解析','Network settings · DNS'])
click(['保存／更新映射','Save / update mapping'])
prefs=ET.fromstring(shell('run-as','com.helix.runtime.cli','cat','shared_prefs/subscription_dns.xml'))
entries=json.loads(next(e.text for e in prefs if e.get('name')=='entries'))
entry=next(e for e in entries if e['host']=='chatgpt.com')
assert entry['expires']-entry['created']==86400000
print(json.dumps({'host':entry['host'],'addresses':entry['addresses'],'hours':24}),flush=True)
shell('am','start','-W','-n','com.helix.agent.developer/com.helix.app.MainActivity')
