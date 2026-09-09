"""Count XML evidence only for test tasks actually named in this Gradle log."""
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
log = Path(sys.argv[1]).read_text()
rows = []
for task in sorted(set(re.findall(r'^> Task (:[\w:-]+)', log, re.M))):
    parts = task.strip(':').split(':')
    if not parts[-1].startswith('test'):
        continue
    folder = Path(*parts[:-1]) / 'build' / 'test-results' / parts[-1]
    reports = list(folder.glob('TEST-*.xml'))
    if not reports:
        continue
    sums = dict(tests=0, failures=0, errors=0, skipped=0)
    for report in reports:
        root = ET.parse(report).getroot()
        for key in sums:
            sums[key] += int(root.get(key, 0))
    rows.append(dict(task=task, reports=len(reports), **sums))
total = {key: sum(row[key] for row in rows) for key in ('tests', 'failures', 'errors', 'skipped')}
print(json.dumps(dict(log=sys.argv[1], tasks=rows, total=total), indent=2))
