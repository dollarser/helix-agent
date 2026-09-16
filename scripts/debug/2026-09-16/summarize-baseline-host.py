"""Summarize repository test XMLs without counting archived baseline workspaces."""
from pathlib import Path
import xml.etree.ElementTree as ET,json
root=Path(__file__).resolve().parents[3]
rows=[]
for p in root.glob('**/build/test-results/**/TEST-*.xml'):
 relative=p.relative_to(root)
 if relative.parts[0]=='build':continue
 e=ET.parse(p).getroot()
 rows.append(dict(path=str(relative),tests=int(e.get('tests','0')),failures=int(e.get('failures','0')),errors=int(e.get('errors','0')),skipped=int(e.get('skipped','0'))))
summary={k:sum(r[k] for r in rows) for k in ['tests','failures','errors','skipped']}
summary['app']={flavor:{k:sum(r[k] for r in rows if r['path'].startswith('app/') and flavor in r['path']) for k in ['tests','failures','errors','skipped']} for flavor in ['testConsumerDebugUnitTest','testDeveloperDebugUnitTest']}
(root/'build/pre-hxa-regression-20260916/host-results.json').write_text(json.dumps(dict(summary=summary,rows=rows),indent=2))
print(json.dumps(summary))
