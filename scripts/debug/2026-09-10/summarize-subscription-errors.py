"""Summarize only scoped host test reports."""
from pathlib import Path
import xml.etree.ElementTree as E
n=f=s=0
for p in Path('runtime/cli-app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
 if 'CodexSmoke' not in p.name and 'CodexModelJobTest' not in p.name:continue
 r=E.parse(p).getroot();a=int(r.get('tests',0));b=int(r.get('failures',0))+int(r.get('errors',0));c=int(r.get('skipped',0));n+=a;f+=b;s+=c;print(p.name,a,b,c)
print('TOTAL',n,'failures',f,'skipped',s);assert n>0 and f==0 and s==0
