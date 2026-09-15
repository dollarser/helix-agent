"""Summarize actual current XML/lint/APK evidence; never infer success from compilation."""
from pathlib import Path
import hashlib
import json
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[3]
paths = ['core/model/build/test-results/test', 'core/policy/build/test-results/test',
         'core/agent/build/test-results/test', 'tools/framework/build/test-results/test',
         'core/storage/build/test-results/testDebugUnitTest', 'app/build/test-results/testConsumerDebugUnitTest',
         'app/build/test-results/testDeveloperDebugUnitTest']
report = {'head': subprocess.check_output(['git','rev-parse','HEAD'], cwd=root, text=True).strip(), 'host': {}, 'lint': {}, 'apks': {}}
for path in paths:
    xmls = sorted((root/path).glob('TEST-*.xml'))
    assert xmls, path
    totals = {k: 0 for k in ['tests','failures','errors','skipped']}
    for xml in xmls:
        node = ET.parse(xml).getroot()
        for key in totals:
            totals[key] += int(node.get(key, '0'))
    assert not totals['failures'] and not totals['errors'], (path, totals)
    report['host'][path] = totals
for flavor in ['consumer','developer']:
    lint = root/f'app/build/reports/lint-results-{flavor}Debug.xml'
    issues = ET.parse(lint).getroot().findall('issue')
    errors = [i.get('id') for i in issues if i.get('severity') in ['Fatal','Error']]
    assert not errors, errors
    report['lint'][flavor] = {'errors':errors, 'issues':len(issues)}
for apk in sorted((root/'app/build/outputs/apk').rglob('*.apk')):
    if '/debug/' in str(apk):
        report['apks'][str(apk.relative_to(root))] = hashlib.sha256(apk.read_bytes()).hexdigest()
(root/'build/hxa200-final-evidence.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report['host'],indent=2))
