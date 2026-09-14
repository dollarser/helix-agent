from pathlib import Path
s=Path('scripts/debug/2026-09-10/run-physical-app-regression.py').read_text()
s=s.replace('a=p.parse_args();', "p.add_argument('--test', default='com.helix.app.ui.PhysicalBackgroundRecoveryDeviceTest');p.add_argument('--host-timeout', type=int, default=420)\na=p.parse_args();")
a=s.index('artifacts=');b=s.index('added=[]',a)
s=s[:a]+'''artifacts=[('com.helix.agent','app/build/outputs/apk/consumer/debug/app-consumer-debug.apk'),('com.helix.agent.test','app/build/outputs/apk/androidTest/consumer/debug/app-consumer-debug-androidTest.apk')]
classes=[a.test]
assert a.test.startswith('com.helix.app.') and all(c.isalnum() or c in '._#$' for c in a.test)
'''+s[b:]
s=s.replace('for pkg,path,digest in artifacts:', 'for pkg,path in artifacts:').replace(';assert actual==digest,path','')
s=s.replace(" raw=device('shell','am','instrument','-w','-r','-e','class',','.join(classes),'com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner')", """ with (out/'instrumentation.log').open('w') as log:
  proc=subprocess.Popen([adb,'-s',a.serial,'shell','am','instrument','-w','-r','-e','class',','.join(classes),'-e','helix.physical.recovery','true','com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner'],stdout=log,stderr=subprocess.STDOUT)
  try:proc.wait(timeout=a.host_timeout)
  except subprocess.TimeoutExpired:
   result['hostTimeoutSeconds']=a.host_timeout
   device('shell','am','force-stop','com.helix.agent');proc.wait(timeout=30)
 raw=(out/'instrumentation.log').read_text()""")
Path('scripts/debug/2026-09-10/run-physical-recovery.py').write_text(s)
