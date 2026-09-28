"""Summarize only synthetic, owned HXA-222 evidence; probe and task calls stay separate."""
import json
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
read = lambda name: (root / name).read_text() if (root / name).exists() else ''
events = read('events.txt')
calls = events.split('START ')[1:]
memory = read('runtime-memory.txt')
tool_calls = []
for row in read('trajectory.txt').splitlines():
    if row.startswith('ToolCallEntity('):
        name = re.search(r', name=([^,]+),', row).group(1)
        arguments, _ = json.JSONDecoder().raw_decode(row.split('argsJson=', 1)[1])
        tool_calls.append((name, arguments.get('path', '')))
readbacks = {}
for filename in ('totals.csv', 'report.md'):
    writes = [i for i, (name, path) in enumerate(tool_calls) if name == 'write' and path.endswith(':' + filename)]
    readbacks[filename] = bool(writes) and any(
        name == 'read' and path.endswith(':' + filename) for name, path in tool_calls[writes[-1] + 1:])
result = {
    'directory': str(root),
    'probe': read('probe.txt'),
    'loadAndInspectMs': read('load-ms.txt'),
    'lifecycle': read('lifecycle.txt'),
    'probeCalls': len(calls[:2]),
    'taskCalls': len(calls[2:]),
    'taskUsage': re.findall(r'Usage\(inputTokens=(\d+), outputTokens=(\d+)\)', '\n'.join(calls[2:])),
    'taskTools': re.findall(r'ToolCallStarted\([^\n]+name=([^)]*)\)', '\n'.join(calls[2:])),
    'taskTerminals': re.findall(r'(?:Completed|Error)\([^\n]*', '\n'.join(calls[2:])),
    'peakPssKiB': max(map(int, re.findall(r'TOTAL PSS:\s*(\d+)', memory)), default=None),
    'peakRssKiB': max(map(int, re.findall(r'TOTAL RSS:\s*(\d+)', memory)), default=None),
    'passed': 'OK (1 test)' in read('real-model-instrumentation.txt'),
    'readAfterFinalWrite': readbacks,
    'totals': read('totals.csv'),
    'report': read('report.md'),
}
(root / 'summary.json').write_text(json.dumps(result, indent=2, ensure_ascii=False))
print(json.dumps(result, indent=2, ensure_ascii=False))
if '--require-pass' in sys.argv:
    assert result['passed'] and all(readbacks.values()), 'Task correctness or durable readback did not pass'
