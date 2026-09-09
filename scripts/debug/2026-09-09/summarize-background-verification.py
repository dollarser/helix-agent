"""Read persisted HXA-177 evidence, without accessing devices or user data."""
import json
from pathlib import Path
import xml.etree.ElementTree as ET

report={}
for module,task in [('core/model','test'),('core/agent','test'),('core/storage','testDebugUnitTest'),('app','testConsumerDebugUnitTest'),('app','testDeveloperDebugUnitTest')]:
    paths=list(Path(module,'build/test-results',task).glob('TEST-*.xml'))
    if not paths: raise RuntimeError(f'No XML for {module}:{task}')
    total={k:0 for k in ['tests','failures','errors','skipped']}
    for p in paths:
        root=ET.parse(p).getroot()
        for k in total: total[k]+=int(root.attrib.get(k,0))
    report[f'{module}:{task}']=total
print(json.dumps(report,indent=2))
