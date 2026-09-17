#!/usr/bin/env python3
"""Summarize local evidence without copying private logs into tracked documents."""
from pathlib import Path
import json
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[3]
report = {}
for flavor in ('Consumer', 'Developer'):
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for path in (root / f'app/build/test-results/test{flavor}DebugUnitTest').glob('TEST-*.xml'):
        suite = ET.parse(path).getroot()
        for key in totals:
            totals[key] += int(suite.attrib.get(key, 0))
    report[flavor] = totals
report['inherited_detekt_files_unchanged'] = {}
for path in (
    'app/src/main/kotlin/com/helix/app/ui/ToolTimelineItem.kt',
    'app/src/main/kotlin/com/helix/app/ui/CommandResultDetailScreen.kt',
    'app/src/main/kotlin/com/helix/app/ui/TasksScreen.kt',
    'app/src/main/kotlin/com/helix/app/proot/CommandResultProjection.kt',
    'app/src/main/kotlin/com/helix/app/proot/CommandResultBrowser.kt',
    'app/src/developer/kotlin/com/helix/app/proot/ProotToolModule.kt',
):
    original = subprocess.check_output(['git', 'show', '73e574f6:' + path], cwd=root)
    report['inherited_detekt_files_unchanged'][path] = original == (root / path).read_bytes()
print(json.dumps(report, indent=2))
