"""Summarize actual host XMLs, lint, and built APKs without counting skipped tests as passes."""
from pathlib import Path
import json,hashlib,xml.etree.ElementTree as E,subprocess
root=Path(__file__).resolve().parents[3]
paths=['core/model/build/test-results/test','core/policy/build/test-results/test','core/agent/build/test-results/test','tools/framework/build/test-results/test','core/storage/build/test-results/testDebugUnitTest','app/build/test-results/testConsumerDebugUnitTest','app/build/test-results/testDeveloperDebugUnitTest']
result={'baseline_head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),'host':{},'lint':{},'apks':{}}
for path in paths:
 files=list((root/path).glob('TEST-*.xml'));assert files,path
 totals={key:0 for key in ['tests','failures','errors','skipped']}
 for p in files:
  node=E.parse(p).getroot()
  for key in totals:totals[key]+=int(node.get(key,0))
 totals['passed']=totals['tests']-totals['skipped']-totals['failures']-totals['errors']
 assert totals['failures']==totals['errors']==0,(path,totals)
 result['host'][path]=totals
for flavor in ['consumer','developer']:
 path=root/f'app/build/reports/lint-results-{flavor}Debug.xml'
 issues=E.parse(path).getroot().findall('issue')
 errors=[i.get('id') for i in issues if i.get('severity') in ['Fatal','Error','Warning']]
 assert not errors,(path,errors)
 result['lint'][flavor]={'errors_or_warnings':errors,'other_issues':len(issues)}
for p in (root/'app/build/outputs/apk').rglob('*.apk'):
 if '/debug/' in str(p):result['apks'][str(p.relative_to(root))]=hashlib.sha256(p.read_bytes()).hexdigest()
(root/'build/hxa201-closeout/host-summary.json').write_text(json.dumps(result,indent=2))
print(json.dumps(result['host'],indent=2))
