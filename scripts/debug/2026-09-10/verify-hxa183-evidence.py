#!/usr/bin/env python3
"""Verify final owned device results, artifact identity and teardown (not historical failed rounds)."""
import hashlib
import json
from pathlib import Path
import re
import sys

for directory, runtime_directory, variant in [(sys.argv[1], sys.argv[3], 'consumer'), (sys.argv[2], sys.argv[4], 'developer')]:
    root = Path(directory)
    result = (root/'instrumentation.txt').read_text()
    assert re.search(r'^OK \(145 tests\)', result, re.M), result
    assert 'run finished: 145 tests, 0 failed, 0 ignored' in (root/'test-logcat.txt').read_text()
    owner = json.loads((root/'owner.json').read_text())
    closed = json.loads((root/'closed.json').read_text())
    assert closed == {'pid': owner['pid'], 'exit': 0}, closed
    hashes = json.loads((root/'artifacts.json').read_text())
    for label, path in [('app', f'app/build/outputs/apk/{variant}/debug/app-{variant}-debug.apk'),
                        ('test', f'app/build/outputs/apk/androidTest/{variant}/debug/app-{variant}-debug-androidTest.apk')]:
        assert hashlib.sha256(Path(path).read_bytes()).hexdigest() == hashes[label]
    runtime = Path(runtime_directory)
    runtime_owner = json.loads((runtime/'owner.json').read_text())
    assert json.loads((runtime/'closed.json').read_text()) == {'pid': runtime_owner['pid'], 'exit': 0}
    assert re.search(r'^OK \([1-9][0-9]* tests?\)', (runtime/'instrumentation.txt').read_text(), re.M)
    assert f"HXA-086 lifecycle acceptance PASSED on {runtime_owner['serial']}" in (runtime/'lifecycle.txt').read_text()
    for path, digest in json.loads((runtime/'proot-artifacts.json').read_text()).items():
        assert hashlib.sha256(Path(path).read_bytes()).hexdigest() == digest
    totals = {}
    for name in ['companion', 'main-proot']:
        text = (runtime/(name+'.txt')).read_text()
        match = re.search(r'^OK \(([1-9][0-9]*) tests?\)', text, re.M)
        assert match and not any(x in text for x in ['FAILURES!!!','Process crashed','INSTRUMENTATION_STATUS_CODE: -3'])
        totals[name] = int(match[1])
    print(json.dumps({'serial': owner['serial'], 'app': 145, **totals, 'lifecycle': 'passed',
                      'closed': True, 'current_artifacts': True}))
