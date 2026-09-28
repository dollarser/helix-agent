"""Bounded model-only BFCL diagnostic; no tools executed, no leaderboard claim."""
import collections
import hashlib
import json
import pathlib
import subprocess
import sys
import time
import types
import urllib.request

from bfcl_eval.constants.enums import Language, ModelStyle
from bfcl_eval.constants.model_config import MODEL_CONFIG_MAPPING
from bfcl_eval.constants.type_mappings import GORILLA_TO_OPENAPI
from bfcl_eval.eval_checker.ast_eval.ast_checker import ast_checker
from bfcl_eval.model_handler.utils import convert_to_tool

ROOT = pathlib.Path(__file__).resolve().parents[3]
REF = ROOT / 'build/public-eval/gorilla'
DATA = REF / 'berkeley-function-call-leaderboard/bfcl_eval/data'
OUT = pathlib.Path(sys.argv[1]).resolve()
OUT.mkdir(parents=True, exist_ok=False)
MODEL = 'Qwen3.8-27B'
SCORER_MODEL = 'Helix-SGLang-Qwen3.8-27B'
MODEL_CONFIG_MAPPING[SCORER_MODEL] = types.SimpleNamespace(underscore_to_dot=True)
selected = []
hashes = {}
for category, count in [('simple_python', 10), ('multiple', 5), ('irrelevance', 5)]:
    path = DATA / f'BFCL_v4_{category}.json'
    hashes[str(path.relative_to(DATA))] = hashlib.sha256(path.read_bytes()).hexdigest()
    rows = [json.loads(line) for line in path.read_text().splitlines() if line.strip()]
    truth = {}
    if category != 'irrelevance':
        answer = DATA / 'possible_answer' / path.name
        hashes[str(answer.relative_to(DATA))] = hashlib.sha256(answer.read_bytes()).hexdigest()
        truth = {r['id']: r['ground_truth'] for r in map(json.loads, answer.read_text().splitlines())}
    selected.extend((category, r, truth.get(r['id'])) for r in rows[:count])
manifest = dict(source_commit=subprocess.check_output(['git', '-C', str(REF), 'rev-parse', 'HEAD'], text=True).strip(),
                model=MODEL, endpoint='http://localhost:30008/v1/chat/completions',
                temperature=0, max_tokens=4096, repetitions=3,
                selected=[r['id'] for _, r, _ in selected], sha256=hashes,
                protocol='Dataset messages, native tool calls, upstream conversion and AST scorer; irrelevance requires no native call. Truncation is FAIL. No tool execution; not full BFCL protocol.')
(OUT / 'manifest.json').write_text(json.dumps(manifest, indent=2))
results = []
for repeat in range(3):
    for category, row, truth in selected:
        started = time.monotonic()
        result = dict(id=row['id'], category=category, repeat=repeat + 1)
        try:
            payload = dict(model=MODEL, messages=row['question'][0],
                           tools=convert_to_tool(row['function'], GORILLA_TO_OPENAPI, ModelStyle.OPENAI_COMPLETIONS),
                           temperature=0, max_tokens=4096)
            request = urllib.request.Request(manifest['endpoint'], json.dumps(payload).encode(), {'Content-Type': 'application/json'})
            with urllib.request.urlopen(request, timeout=120) as response:
                raw = json.load(response)
            result['response'] = raw
            choice = raw['choices'][0]
            calls = choice['message'].get('tool_calls') or []
            decoded = [{c['function']['name']: json.loads(c['function']['arguments'])} for c in calls]
            verdict = ({'valid': not calls} if category == 'irrelevance' else
                       ast_checker(row['function'], decoded, truth, Language.PYTHON, category, SCORER_MODEL))
            result['verdict'] = verdict
            result['status'] = 'PASS' if verdict['valid'] and choice['finish_reason'] != 'length' else 'FAIL'
        except Exception as exc:
            result.update(status='ERROR', error=f'{type(exc).__name__}: {exc}')
        result['elapsed_seconds'] = round(time.monotonic() - started, 3)
        results.append(result)
        with (OUT / 'results.jsonl').open('a') as stream:
            stream.write(json.dumps(result, ensure_ascii=False) + '\n')
        print(result['id'], result['repeat'], result['status'], result['elapsed_seconds'], flush=True)
summary = dict(total=len(results), counts=dict(collections.Counter(r['status'] for r in results)),
               categories={c: dict(collections.Counter(r['status'] for r in results if r['category'] == c)) for c in ['simple_python', 'multiple', 'irrelevance']})
(OUT / 'summary.json').write_text(json.dumps(summary, indent=2))
print(json.dumps(summary), flush=True)
