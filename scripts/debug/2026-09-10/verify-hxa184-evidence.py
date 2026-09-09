#!/usr/bin/env python3
"""Verify final host and separately owned app/library evidence without borrowing a device."""
import hashlib
import json
from pathlib import Path
import re

root=Path('build/debug/2026-09-10/hxa184')
log=(root/'final-host-gates.log').read_text()
assert 'BUILD SUCCESSFUL' in log and 'BUILD FAILED' not in log
host=json.loads((root/'final-host-counts.json').read_text())['total']
assert host['failures']==host['errors']==0 and host['tests']==2796 and host['skipped']==8
expected={'core-storage','feature-files','feature-browser','runtime-quickjs','tools-android','tools-automation',
          'automation-force-stop-recovery'}
summary={'host':host,'devices':[]}
for api,variant in [(29,'consumer'),(36,'developer')]:
    folder=root/f'accepted{api}'
    closed=json.loads((folder/'closed.json').read_text())
    assert closed['exit']==0
    app=(folder/'instrumentation.txt').read_text()
    assert re.search(r'^OK \(163 tests\)',app,re.M) and 'FAILURES!!!' not in app
    device_log=(folder/'test-logcat.txt').read_text()
    assert '163 tests, 0 failed, 0 ignored' in device_log
    for name,path in [('app.apk',f'app/build/outputs/apk/{variant}/debug/app-{variant}-debug.apk'),
                      ('test.apk',f'app/build/outputs/apk/androidTest/{variant}/debug/app-{variant}-debug-androidTest.apk')]:
        assert hashlib.sha256((folder/name).read_bytes()).digest()==hashlib.sha256(Path(path).read_bytes()).digest()
    modules=json.loads((folder/'module-results.json').read_text())
    assert {row['suite'] for row in modules}==expected
    for row in modules:
        raw=(folder/(row['suite']+'.txt')).read_text()
        assert re.search(r'^OK \([1-9][0-9]* tests?\)',raw,re.M)
        assert not any(x in raw for x in ['FAILURES!!!','INSTRUMENTATION_FAILED','Process crashed'])
        assert row['completed']>0
        assert row['completed']==raw.count('INSTRUMENTATION_STATUS_CODE: 0')
        assert row['assumptions']==raw.count('INSTRUMENTATION_STATUS_CODE: -4')
        assert 'INSTRUMENTATION_STATUS_CODE: -3' not in raw
        assert row['reportedTests']==row['completed']+row['assumptions']
        assert row['assumptions']==(2 if row['suite']=='feature-browser' else 0),row
    assert 'hxa093ReadyForHostKill=1' in (folder/'automation-force-stop-setup.txt').read_text()
    assert 'live pid=' in (folder/'automation-force-stop.txt').read_text()
    for path,digest in json.loads((folder/'module-artifacts.json').read_text()).items():
        assert hashlib.sha256(Path(path).read_bytes()).hexdigest()==digest,path
    summary['devices'].append({'api':api,'appPassed':163,'modules':modules,'closed':closed})
print(json.dumps(summary,indent=2))
