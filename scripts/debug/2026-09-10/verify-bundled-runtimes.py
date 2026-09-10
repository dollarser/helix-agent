"""Host-only packaging, UI compilation and lint. No device operations."""
from pathlib import Path
import subprocess,zipfile,hashlib,sys
root=Path(__file__).resolve().parents[3]
paths=list(root.glob('app/src/*/kotlin/com/helix/app/companions/*.kt'))+[root/'app/build.gradle.kts',root/'app/src/main/kotlin/com/helix/app/ui/SettingsScreen.kt']
out=root/'build/debug/2026-09-10/bundled-runtimes';out.mkdir(parents=True,exist_ok=True)
commands=[['./gradlew','spotlessApply','-PspotlessIdeHook='+','.join(map(str,paths))],['./gradlew',':app:testDeveloperDebugUnitTest','--tests','*RuntimeApkPolicyTest',':app:assembleDeveloperDebug',':app:assembleConsumerDebug',':app:lintDeveloperDebug',':app:lintConsumerDebug']]
if '--artifacts-only' not in sys.argv:
 for i,cmd in enumerate(commands):
  with (out/f'{i}.log').open('w') as log:r=subprocess.run(cmd,cwd=root,stdout=log,stderr=subprocess.STDOUT)
  print(i,r.returncode,flush=True)
  if r.returncode:raise SystemExit(r.returncode)
with zipfile.ZipFile(root/'app/build/outputs/apk/developer/debug/app-developer-debug.apk') as apk:
 for name,module in [('subscriptions','cli'),('proot','proot')]:
  embedded=apk.read(f'assets/companions/{name}.apk')
  source=(root/f'runtime/{module}-app/build/outputs/apk/debug/{module}-app-debug.apk').read_bytes()
  assert embedded==source
  print(name,len(embedded),hashlib.sha256(embedded).hexdigest())
with zipfile.ZipFile(root/'app/build/outputs/apk/consumer/debug/app-consumer-debug.apk') as apk:
 assert not any(p.startswith('assets/companions/') for p in apk.namelist())
print('Exact companion bytes bundled; consumer unchanged.')
# Verify actual APK signatures as well as the pure rejection policy.
import os,re,xml.etree.ElementTree as ET
properties=dict(line.split('=',1) for line in (root/'local.properties').read_text().splitlines() if '=' in line)
sdk=Path(os.environ.get('ANDROID_HOME') or properties['sdk.dir'])
signer=sorted((sdk/'build-tools').glob('*/apksigner'))[-1]
certificates=[]
for relative in ['app/build/outputs/apk/developer/debug/app-developer-debug.apk','runtime/cli-app/build/outputs/apk/debug/cli-app-debug.apk','runtime/proot-app/build/outputs/apk/debug/proot-app-debug.apk']:
 result=subprocess.check_output([str(signer),'verify','--print-certs',str(root/relative)],text=True)
 cert=set(re.findall(r'Signer #\d+ certificate SHA-256 digest: (\w+)',result))
 assert cert
 certificates.append(cert)
assert certificates[0]==certificates[1]==certificates[2]
result=ET.parse(root/'app/build/test-results/testDeveloperDebugUnitTest/TEST-com.helix.app.companions.RuntimeApkPolicyTest.xml').getroot()
assert [int(result.get(k,0)) for k in ['tests','failures','errors','skipped']]==[4,0,0,0]
print('All three APK signatures verified and identical; policy tests 4 passed.')
for variant in ['consumerDebug','developerDebug']:
 manifests=list((root/'app/build/intermediates/merged_manifests'/variant).glob('**/AndroidManifest.xml'))
 assert len(manifests)==1
 text=manifests[0].read_text()
 assert ('android.permission.REQUEST_INSTALL_PACKAGES' in text)==(variant=='developerDebug')
 if variant=='developerDebug':
  assert '.runtime-apks' in text and '.fileprovider' in text
print('Variant manifests checked; existing file-sharing authority preserved.')
