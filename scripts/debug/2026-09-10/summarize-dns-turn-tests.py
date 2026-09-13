"""Report counts from the targeted default-setting regression run."""
from pathlib import Path
import xml.etree.ElementTree as ET
paths=[('app/build/test-results/testDeveloperDebugUnitTest', n) for n in ['RunControlStoreTest','TurnBudgetTrackerTest','ContextRequestTest']] + [('runtime/cli-app/build/test-results/testDebugUnitTest','SubscriptionDnsSettingsTest')]
total=0
for directory,name in paths:
    files=list(Path(directory).glob('TEST-*'+name+'.xml'));assert len(files)==1
    root=ET.parse(files[0]).getroot()
    counts={k:int(root.get(k,'0')) for k in ['tests','failures','errors','skipped']}
    assert counts['failures']==counts['errors']==counts['skipped']==0
    total+=counts['tests'];print(name,counts)
print('TOTAL',total)
