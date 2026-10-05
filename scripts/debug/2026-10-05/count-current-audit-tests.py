from pathlib import Path
import xml.etree.ElementTree as E
roots=['app/build/test-results/testDeveloperDebugUnitTest','app/build/test-results/testConsumerDebugUnitTest','extensions/mobile-use/build/test-results/testDebugUnitTest','tools/files/build/test-results/test','core/policy/build/test-results/test','extensions/plugin/build/test-results/test','extensions/mcp/build/test-results/test','extensions/a2a/build/test-results/testDebugUnitTest','runtime/cli-app/build/test-results/testDebugUnitTest']
total=0
for root in roots:
 stats=[0,0,0,0]
 for path in Path(root).glob('TEST-*.xml'):
  e=E.parse(path).getroot()
  for i,key in enumerate(['tests','failures','errors','skipped']):stats[i]+=int(e.get(key,0))
 total+=stats[0];print(root,stats)
print('Total',total)
