"""Summarize saved synthetic-fixture evidence; never infer a task pass from tool-call presence."""
import json
from pathlib import Path
import re
import sys
p=Path(sys.argv[1])
events=(p/'events.txt').read_text()
memory=(p/'runtime-memory.txt').read_text()
usage=[dict(inputTokens=int(a), outputTokens=int(b)) for a,b in re.findall(r'Usage\(inputTokens=(\d+), outputTokens=(\d+)\)',events)]
ends=[dict(ms=int(ms), reason=reason) for ms,reason in re.findall(r'(\d+)ms Completed\(finishReason=([^\)]+)\)',events)]
pss=[int(n) for n in re.findall(r'TOTAL PSS:\s+(\d+)',memory)]
rss=[int(n) for n in re.findall(r'TOTAL RSS:\s+(\d+)',memory)]
result={'loadAndInspectMs':int((p/'load-ms.txt').read_text()),'modelCallsStarted':events.count('START '),'usage':usage,'modelCallEnds':ends,'tools':re.findall(r'ToolCallStarted\(.*?name=([^\)]+)\)',events),'sampledPeakPssKiB':max(pss,default=0),'sampledPeakRssKiB':max(rss,default=0),'instrumentationPassed':'OK (1 test)' in (p/'real-model-instrumentation.txt').read_text()}
(p/'summary.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))
