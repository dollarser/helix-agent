"""Summarize synthetic public pilot artifacts without editing raw evidence."""
import collections
import hashlib
import json
from pathlib import Path
import statistics

root = Path(__file__).resolve().parents[3]
path = root / 'build/public-eval/bfcl-pilot-20260928'
rows = [json.loads(line) for line in (path / 'results.jsonl').read_text().splitlines()]
summary = {
    'counts': dict(collections.Counter(row['status'] for row in rows)),
    'median_seconds': statistics.median(row['elapsed_seconds'] for row in rows),
    'mean_seconds': statistics.mean(row['elapsed_seconds'] for row in rows),
    'max_seconds': max(row['elapsed_seconds'] for row in rows),
    'finish_reasons': dict(collections.Counter(row['response']['choices'][0]['finish_reason'] for row in rows)),
    'sha256': {name: hashlib.sha256((path / name).read_bytes()).hexdigest() for name in ['manifest.json', 'results.jsonl']},
}
print(json.dumps(summary, indent=2))
for run in sorted((root / 'build/public-eval').glob('androidworld-pilot*')):
    results = run / 'androidworld-results.json'
    if not results.exists():
        continue
    for row in json.loads(results.read_text()):
        agent = row.get('agent', {})
        exposed = agent.get('toolVersions', {})
        print(json.dumps(dict(run=run.name, task=row['task'], status=row['status'],
                              before=row.get('before'), after=row.get('after'), score=row.get('score'),
                              turn=agent.get('turnState'), elapsed_ms=agent.get('elapsedMs'),
                              exposed_count=len(exposed), exposed_ui=[n for n in exposed if n.startswith('ui.')],
                              registered_ui=agent.get('registeredUiTools'),
                              calls=[f"{c['name']}:{c['state']}" for c in agent.get('calls', [])])))
