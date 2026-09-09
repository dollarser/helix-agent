import hashlib
import json
from pathlib import Path
import re
for variant,api in [('consumer',29),('developer',36)]:
 root=Path(f'build/debug/2026-09-10/hxa182-final{api}')
 result=(root/'instrumentation.txt').read_text()
 assert re.search(r'^OK \(47 tests\)',result,re.M), result
 assert 'Process crashed' in (root/'recovery-setup.txt').read_text()
 assert (root/'process-stop.txt').read_text().split('pid=')[-1].isdigit()
 assert json.loads((root/'closed.json').read_text())['exit']==0
 hashes=json.loads((root/'artifacts.json').read_text())
 for key,path in [('app',f'app/build/outputs/apk/{variant}/debug/app-{variant}-debug.apk'),('test',f'app/build/outputs/apk/androidTest/{variant}/debug/app-{variant}-debug-androidTest.apk')]:
  assert hashlib.sha256(Path(path).read_bytes()).hexdigest()==hashes[key]
 print(f'{variant} API{api}: 47 passed, actual death setup, owned emulator closed, current APK hashes matched')
