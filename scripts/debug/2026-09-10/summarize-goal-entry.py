"""Summarize selected JUnit XML without treating compiled Android tests as executed."""
from pathlib import Path
import xml.etree.ElementTree as ET
paths=[p for p in Path('app/build/test-results/testDeveloperDebugUnitTest').glob('TEST-*.xml') if 'Goal' in p.name or 'RunControlStoreTest' in p.name]
assert paths
counts=[0,0,0,0]
for p in sorted(paths):
 r=ET.parse(p).getroot()
 values=[int(r.get(k,0)) for k in ('tests','failures','errors','skipped')]
 counts=[a+b for a,b in zip(counts,values)]
 print(r.get('name'),values)
print('TOTAL tests/failures/errors/skipped:',counts)
assert counts[1:]==[0,0,0]
