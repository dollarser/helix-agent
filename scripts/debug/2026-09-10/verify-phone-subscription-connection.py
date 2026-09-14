"""Update the selected phone and run only the existing connection probe; no conversation or capability suite."""
from pathlib import Path
import os,subprocess,shlex
adb=str(Path(os.environ['ANDROID_HOME'])/'platform-tools/adb');serial=os.environ['HELIX_PHONE_SERIAL'];assert not serial.startswith('emulator-')
def device(*a):return subprocess.check_output([adb,'-s',serial,*a],text=True,timeout=180)
for apk in ['runtime/cli-app/build/outputs/apk/debug/cli-app-debug.apk','app/build/outputs/apk/developer/debug/app-developer-debug.apk','app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk']:
 print(device('install','-r',apk),flush=True)
try:
 args=['am','instrument','-w','-r','-e','class','com.helix.app.provider.CodexSubscriptionProviderRealAccountDeviceTest','-e','realSubscription','true','-e','probeOnly','true','com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner']
 result=device('shell',shlex.join(args))
 Path('build/debug/2026-09-10/subscription-error-recovery/phone-probe.log').write_text(result)
 print(result,flush=True)
 assert "OK (1 test)" in result and "FAILURES" not in result and "INSTRUMENTATION_STATUS_CODE: -3" not in result, "Connection probe did not pass"
finally:
 print(device('uninstall','com.helix.agent.developer.test'),flush=True)
