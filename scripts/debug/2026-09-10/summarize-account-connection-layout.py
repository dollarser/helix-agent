"""Summarize exact host test suites for this checkpoint."""
from pathlib import Path
import xml.etree.ElementTree as E
n=f=s=0
for folder, names in [('runtime/cli-app/build/test-results/testDebugUnitTest', ['CodexSmokeCatalogTest','CodexSmokeStreamTest','CodexSmokeRefreshTest']),('app/build/test-results/testDeveloperDebugUnitTest',['ProviderConnectionCheckTest','CodexCapabilityProbeTest'])]:
 for p in Path(folder).glob('TEST-*.xml'):
  if not any(p.name.endswith('.'+name+'.xml') for name in names):continue
  r=E.parse(p).getroot();a=int(r.get('tests',0));b=int(r.get('failures',0))+int(r.get('errors',0));c=int(r.get('skipped',0));n+=a;f+=b;s+=c;print(p.name,a,b,c)
print('TOTAL',n,'failures',f,'skipped',s);assert n==33 and f==0 and s==0
