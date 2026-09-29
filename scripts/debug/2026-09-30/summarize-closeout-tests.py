from pathlib import Path
import xml.etree.ElementTree as ET
paths=['core/model/build/test-results/test','core/agent/build/test-results/test','core/storage/build/test-results/testDebugUnitTest','runtime/quickjs/build/test-results/testDebugUnitTest','tools/automation/build/test-results/testDebugUnitTest','app/build/test-results/testConsumerDebugUnitTest','app/build/test-results/testDeveloperDebugUnitTest']
for directory in paths:
 values=dict.fromkeys(['tests','failures','errors','skipped'],0)
 for p in Path(directory).glob('TEST-*.xml'):
  r=ET.parse(p).getroot()
  for k in values: values[k]+=int(r.get(k,'0'))
 print(directory, values, 'passed=',values['tests']-values['failures']-values['errors']-values['skipped'])
