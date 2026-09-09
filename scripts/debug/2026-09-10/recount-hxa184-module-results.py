#!/usr/bin/env python3
"""Recount preserved raw results: AndroidJUnitRunner assumption status is -4, not -3."""
import json
from pathlib import Path
for api in [29,36]:
    folder=Path(f'build/debug/2026-09-10/hxa184/accepted{api}')
    path=folder/'module-results.json'
    rows=json.loads(path.read_text())
    for row in rows:
        raw=(folder/(row['suite']+'.txt')).read_text()
        assert 'INSTRUMENTATION_STATUS_CODE: -3' not in raw
        row['assumptions']=raw.count('INSTRUMENTATION_STATUS_CODE: -4')
        assert row['reportedTests']==row['completed']+row['assumptions']
    path.write_text(json.dumps(rows,indent=2))
