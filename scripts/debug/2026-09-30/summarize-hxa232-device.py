#!/usr/bin/env python3
"""Summarize archived raw instrumentation status; never infer success from APK builds."""
from collections import Counter
import json
from pathlib import Path
import re
import sys
for directory in map(Path, sys.argv[1:]):
    raw = (directory / 'instrumentation.txt').read_text()
    counts = Counter()
    failures = []
    previous = 0
    for match in re.finditer(r'^INSTRUMENTATION_STATUS_CODE: (-?\d+)$', raw, re.M):
        block = raw[previous:match.start()]
        previous = match.end()
        code = int(match.group(1))
        if code == 1:
            continue
        test_class = re.search(r'^INSTRUMENTATION_STATUS: class=(.+)$', block, re.M)
        test = re.search(r'^INSTRUMENTATION_STATUS: test=(.+)$', block, re.M)
        if test_class is None or test is None:
            continue
        outcome = 'pass' if code == 0 else 'skip' if code in (-3, -4) else 'fail'
        counts[(test_class.group(1), outcome)] += 1
        if outcome == 'fail':
            failures.append(test.group(1))
    report = {
        'directory': str(directory),
        'counts': {outcome: sum(v for (c, o), v in counts.items() if o == outcome)
                   for outcome in ('pass', 'fail', 'skip')},
        'classes': {c: {o: counts[(c, o)] for o in ('pass', 'fail', 'skip')}
                    for c in sorted({c for c, o in counts})},
        'failures': failures,
        'artifacts': json.loads((directory / 'artifacts.json').read_text()),
        'closed': json.loads((directory / 'closed.json').read_text())
                  if (directory / 'closed.json').exists() else None,
    }
    (directory / 'summary.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))
