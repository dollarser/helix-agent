"""Audit the opt-in synthetic JNI request captures without printing prompt contents."""
import json
from pathlib import Path
import sys

root = Path(sys.argv[1])
files = sorted(root.glob('request-*.json'))
assert files, 'No raw JNI requests captured'
history_calls = 0
tool_results = 0
for path in files:
    request = json.loads(path.read_text())
    assert len(path.read_bytes()) <= 1024 * 1024
    for message in request['history']:
        tool_results += message['role'] == 'tool'
        for call in message.get('calls', []):
            args = json.loads(call['arguments'])
            assert '__helix_intent' not in args, f'Presentation metadata replayed in {path.name}'
            history_calls += 1
result = {'requests': len(files), 'historyCalls': history_calls, 'toolResults': tool_results,
          'presentationMetadataReplayed': False}
assert history_calls > 0 and tool_results > 0, 'Expected a real tool trajectory'
(root / 'request-audit.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result))
