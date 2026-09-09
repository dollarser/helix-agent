"""Read JUnit and owned emulator evidence for HXA-178; no device operations."""
from pathlib import Path
import json
import re
import xml.etree.ElementTree as ET
roots=['core/model','core/agent','core/storage','app']
for root in roots:
 groups={}
 for p in Path(root,'build/test-results').glob('*/TEST-*.xml'):
  x=ET.parse(p).getroot()
  counts=groups.setdefault(p.parent.name,[0,0,0,0])
  for i,key in enumerate(['tests','failures','errors','skipped']): counts[i]+=int(x.get(key,0))
 print(root,groups)
for p in sorted(Path('build/debug/2026-09-09').glob('hxa178-*/instrumentation.txt')):
 text=p.read_text()
 passed=re.search(r'^OK \((\d+) tests?\)',text,re.M)
 closed=p.parent/'closed.json'
 print(p.parent.name,passed[0] if passed else 'FAILED',json.loads(closed.read_text()) if closed.exists() else 'NOT CLOSED')
