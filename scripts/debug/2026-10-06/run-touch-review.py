import subprocess,time,json,sys
from pathlib import Path
out=Path('build/accessibility-click-regression');out.mkdir(exist_ok=True)
serial='emulator-5554';pkg='com.helix.agent.developer';service=pkg+'/com.helix.extensions.mobileuse.automation.HelixAccessibilityService'
def adb(*a):return subprocess.check_output(['adb','-s',serial,*a],text=True,timeout=40).strip()
orig={k:adb('shell','settings','get','secure',k) for k in ['enabled_accessibility_services','accessibility_enabled']}
(out/'settings.json').write_text(json.dumps(orig))
others=[x for x in orig['enabled_accessibility_services'].split(':') if x not in ['null','',service]]
pid=subprocess.run(['adb','-s',serial,'shell','pidof','shizuku_server'],capture_output=True,text=True,timeout=40).stdout.strip()
if pid: adb('shell','kill',pid)
installer='--installer' in sys.argv
extra=['-e','helixInstallerTouch','true','-e','helixTouchX','885','-e','helixTouchY','1380'] if installer else []
log=out/('installer-touch.log' if installer else 'fixed-touch.log')
with log.open('w') as f:
 p=subprocess.Popen(['adb','-s',serial,'shell','am','instrument','-w','-r','-e','class','com.helix.app.eval.AccessibilityTouchReviewDeviceTest','-e','helixTouchReview','true',*extra,pkg+'.test/com.helix.app.HelixAndroidJUnitRunner'],stdout=f,stderr=subprocess.STDOUT)
 try:
  for _ in range(150):
   if 'INSTRUMENTATION_STATUS_CODE: 1' in log.read_text() or p.poll() is not None:break
   time.sleep(.1)
  adb('shell','settings','put','secure','enabled_accessibility_services',':'.join(others) or 'null')
  adb('shell','settings','put','secure','enabled_accessibility_services',':'.join(others+[service]))
  adb('shell','settings','put','secure','accessibility_enabled','1')
  p.wait(timeout=120)
 finally:
  if p.poll() is None:p.terminate();p.wait(timeout=10)
  for k,v in orig.items():adb('shell','settings','put','secure',k,v)
  if pid:adb('shell','/data/local/tmp/shizuku')
print(log.read_text())
(out/('installer-logcat.txt' if installer else 'touch-logcat.txt')).write_text(adb('logcat','-d','-s','System.out:I'))
if 'OK (1 test)' not in log.read_text():raise SystemExit(1)
