from pathlib import Path
import json

out = []
for trial in ['baseline', 'baseline-oracle', 'optimized-valid', 'final', 'keyboard-fixed', 'region-fixed', 'region-repeat']:
    for case in ['gui', 'files', 'recovery', 'combined', 'keyboard']:
        path = Path(f'build/model-capability-eval/{trial}-{case}.json')
        if not path.exists():
            continue
        data = json.loads(path.read_text())
        usage = [json.loads(value) if value and value != 'null' else {} for value in data.get('usage', [])]
        def tokens(key):
            if not usage or any(key not in item for item in usage):
                return None
            return sum(item[key] for item in usage)
        calls = data['calls']
        failed = (sum(call['resultStatus'] == 'FAILED' for call in calls)
                  if all(call.get('resultStatus') is not None for call in calls) else None)
        out.append(dict(trial=trial, case=case, passed=data['passed'], seconds=round(data['elapsedMs'] / 1000, 2),
                        modelCalls=data['trajectory']['modelCalls'], toolCalls=data['trajectory']['toolCalls'],
                        failedTools=failed, inputTokens=tokens('inputTokens'), outputTokens=tokens('outputTokens')))
print(json.dumps(out, ensure_ascii=False, indent=2))
Path('build/model-capability-eval/summary.json').write_text(json.dumps(out, ensure_ascii=False, indent=2))
