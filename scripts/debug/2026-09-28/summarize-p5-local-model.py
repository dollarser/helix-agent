#!/usr/bin/env python3
"""Summarize one P5 model run without inventing native prefill/TTFT/decode metrics."""
import csv
import hashlib
import json
import re
import sys
from pathlib import Path


root = Path(sys.argv[1])
config = json.loads((root / 'p5-config.json').read_text())


def read(name):
    path = root / name
    return path.read_text(errors='replace') if path.exists() else ''


def digest_bytes(value):
    return hashlib.sha256(value).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()


def parse_calls(text):
    calls = []
    for chunk in text.split('START ')[1:]:
        header, *lines = chunk.splitlines()
        generation_id = header.split()[0]
        context = int(re.search(r'context=(\d+)', header).group(1))
        timed = []
        for line in lines:
            match = re.match(r'(\d+)ms\s+(.*)', line)
            if match:
                timed.append((int(match.group(1)), match.group(2)))
        usage = re.search(r'Usage\(inputTokens=(\d+), outputTokens=(\d+)\)', chunk)
        terminal = next(
            ((ms, event) for ms, event in reversed(timed) if event.startswith(('Completed(', 'Error(', 'Refusal('))),
            (None, None),
        )
        text_values = [event.removeprefix('TextDelta(text=').removesuffix(')') for _, event in timed if event.startswith('TextDelta(text=')]
        calls.append({
            'generationId': generation_id,
            'contextTokens': context,
            # The native API returns one final JSON. This is only the first decoded Kotlin
            # event after native completion, never true time-to-first-token.
            'firstDecodedEventAfterNativeReturnMs': timed[0][0] if timed else None,
            'wallMs': terminal[0],
            'terminal': terminal[1],
            'inputTokens': int(usage.group(1)) if usage else None,
            'outputTokens': int(usage.group(2)) if usage else None,
            'text': ''.join(text_values),
            'tools': re.findall(r'ToolCallStarted\([^\n]+name=([^\)]+)\)', chunk),
        })
    for call in calls:
        wall = call['wallMs']
        output = call['outputTokens']
        call['effectiveEndToEndOutputTokensPerSecond'] = (
            round(output * 1000 / wall, 3) if wall and output is not None else None
        )
    return calls


def parse_phases(text):
    events = []
    for line in text.splitlines():
        try:
            when, name, state = line.split('\t', 2)
            events.append((int(when), name, state))
        except ValueError:
            continue
    intervals = {}
    starts = {}
    for when, name, state in events:
        if state == 'start':
            starts[name] = when
        elif state == 'end' and name in starts:
            intervals[name] = (starts[name], when)
    return events, intervals


def parse_memory(text):
    samples = []
    chunks = re.split(r'(?=DEVICE_UPTIME_MS=)', text)
    for chunk in chunks:
        time_match = re.search(r'DEVICE_UPTIME_MS=(\d+)', chunk)
        pss = re.search(r'TOTAL PSS:\s+(\d+)', chunk)
        rss = re.search(r'TOTAL RSS:\s+(\d+)', chunk)
        if time_match and (pss or rss):
            samples.append({
                'uptimeMs': int(time_match.group(1)),
                'pssKiB': int(pss.group(1)) if pss else None,
                'rssKiB': int(rss.group(1)) if rss else None,
            })
    return samples


def peak(samples, interval=None):
    rows = samples
    if interval:
        start, end = interval
        rows = [row for row in rows if start <= row['uptimeMs'] <= end]
    return {
        'sampleCount': len(rows),
        'peakPssKiB': max((row['pssKiB'] or 0 for row in rows), default=None),
        'peakRssKiB': max((row['rssKiB'] or 0 for row in rows), default=None),
    }


def trajectory_truth(text):
    turn = next((line for line in text.splitlines() if line.startswith('TurnEntity(')), '')
    state = re.search(r'\bstate=([^,\)]+)', turn)
    error = re.search(r'\berrorCode=([^,\)]+)', turn)
    tools = []
    for line in text.splitlines():
        if not line.startswith('ToolCallEntity('):
            continue
        name = re.search(r', name=([^,]+),', line)
        state_match = re.search(r', state=([^,\)]+)', line)
        tools.append({'name': name.group(1) if name else None, 'state': state_match.group(1) if state_match else None})
    return {
        'turnState': state.group(1) if state else None,
        'turnError': error.group(1) if error else None,
        'persistedTools': tools,
    }


def aggregation_oracle():
    totals_path = root / 'totals.csv'
    report = read('report.md')
    totals_ok = False
    if totals_path.exists():
        rows = [[cell.strip().strip('"') for cell in row] for row in csv.reader(totals_path.read_text().splitlines()) if row]
        try:
            totals_ok = (
                [cell.lower() for cell in rows[0]] == ['region', 'orders', 'returns', 'net'] and
                rows[1][0] == 'East' and [float(value) for value in rows[1][1:]] == [150.0, 10.0, 140.0] and
                rows[2][0] == 'West' and [float(value) for value in rows[2][1:]] == [120.0, 20.0, 100.0]
            )
        except (IndexError, ValueError):
            totals_ok = False
    tool_rows = []
    for line in read('trajectory.txt').splitlines():
        if not line.startswith('ToolCallEntity('):
            continue
        name_match = re.search(r', name=([^,]+),', line)
        try:
            arguments, _ = json.JSONDecoder().raw_decode(line.split('argsJson=', 1)[1])
        except (IndexError, json.JSONDecodeError):
            arguments = {}
        tool_rows.append((name_match.group(1) if name_match else None, arguments.get('path', '')))
    readbacks = {}
    for filename in ('totals.csv', 'report.md'):
        writes = [i for i, (name, path) in enumerate(tool_rows) if name == 'write' and path.endswith(':' + filename)]
        readbacks[filename] = bool(writes) and any(
            name == 'read' and path.endswith(':' + filename) for name, path in tool_rows[writes[-1] + 1:]
        )
    return {
        'totalsCorrect': totals_ok,
        'reportContainsGrandNet240': '240' in report,
        'readAfterFinalWrite': readbacks,
        'passed': totals_ok and '240' in report and all(readbacks.values()),
    }


calls = parse_calls(read('events.txt'))
phase_events, intervals = parse_phases(read('baseline-phases.tsv'))
memory = parse_memory(read('runtime-memory.txt'))
truth = trajectory_truth(read('trajectory.txt'))
aggregation_output_oracle = aggregation_oracle()
aggregation_durable_completed = truth['turnState'] == 'COMPLETED'
aggregation_verified = aggregation_output_oracle['passed'] and aggregation_durable_completed
aggregation_calls = calls[2:]
aggregation_wall_sum = sum(call['wallMs'] or 0 for call in aggregation_calls)
aggregation_output = sum(call['outputTokens'] or 0 for call in aggregation_calls)
aggregation_interval = intervals.get('aggregation-task')
if aggregation_interval is None:
    starts = [when for when, name, state in phase_events if name == 'aggregation-task' and state == 'start']
    ends = [when for when, name, state in phase_events if name.startswith('generation:') and state == 'end']
    if starts and ends and max(ends) >= starts[-1]:
        aggregation_interval = (starts[-1], max(ends))

no_tool = calls[0] if calls else None
no_tool_oracle = bool(
    no_tool and
    (no_tool.get('text') or '').strip().lower() == 'ok' and
    (no_tool.get('terminal') or '').startswith('Completed(')
)
lifecycle = json.loads(read('p5-lifecycle.json') or '{}')
artifacts = json.loads(read('artifacts.json') or '{}')
device = json.loads(read('device-properties.json') or '{}')
emulator = json.loads(read('emulator-config.json') or '{}')
closed = json.loads(read('closed.json') or '{}')
instrumentation = read('real-model-instrumentation.txt')
observed_tests = None
match = re.search(r'(?:Tests run:\s*|OK \()(\d+)', instrumentation)
if match:
    observed_tests = int(match.group(1))
required_lifecycle_fields = ('cancelToExitMs', 'unloadMs', 'secondLoadMs', 'terminateMs')
evidence_complete = bool(
    artifacts.get('app') and
    artifacts.get('test') and
    device.get('api') and
    closed.get('exit') == 0 and
    observed_tests == 2 and
    calls and
    read('trajectory.txt').strip() and
    all(isinstance(lifecycle.get(key), int) and lifecycle[key] >= 0 for key in required_lifecycle_fields)
)
result = {
    'model': config,
    'device': device,
    'emulator': emulator,
    'artifacts': artifacts,
    'contextTokens': 4096,
    'threads': 2,
    'nativeTimingObservability': {
        'prefillMs': None,
        'trueFirstTokenMs': None,
        'decodeOnlyMs': None,
        'decodeTokensPerSecond': None,
        'reason': 'native generate returns one final JSON after prompt decode and token generation; no intermediate timing event',
    },
    'coldLoadMs': int(read('cold-load-ms.txt').strip()) if read('cold-load-ms.txt').strip().isdigit() else None,
    'warmReuseMs': int(read('warm-reuse-ms.txt').strip()) if read('warm-reuse-ms.txt').strip().isdigit() else None,
    'loadAndInspectMs': int(read('load-ms.txt').strip()) if read('load-ms.txt').strip().isdigit() else None,
    'noTool': {
        'prompt': 'Reply with the single word: ok',
        'oraclePassed': no_tool_oracle,
        'call': no_tool,
    },
    'capabilityToolCall': calls[1] if len(calls) > 1 else None,
    'aggregation': {
        'promptFixture': 'LocalModelRealTaskDeviceTest orders.csv + returns.csv by-region aggregation',
        'turn': truth,
        'modelCalls': aggregation_calls,
        'modelCallWallMsSum': aggregation_wall_sum,
        'outputTokensSum': aggregation_output,
        'effectiveEndToEndOutputTokensPerSecond': (
            round(aggregation_output * 1000 / aggregation_wall_sum, 3) if aggregation_wall_sum else None
        ),
        'taskWallMsObserved': (aggregation_interval[1] - aggregation_interval[0]) if aggregation_interval else None,
        'oracle': {
            **aggregation_output_oracle,
            'durableTurnCompleted': aggregation_durable_completed,
            'passed': aggregation_verified,
        },
    },
    'memory': {
        'overall': peak(memory),
        'loadedIdle': peak(memory, intervals.get('loaded-idle')),
        'aggregationTask': peak(memory, aggregation_interval),
    },
    'lifecycle': lifecycle,
    'evidenceCompleteness': {
        'complete': evidence_complete,
        'ownedEmulatorClosed': closed.get('exit') == 0,
        'observedInstrumentationTests': observed_tests,
        'expectedInstrumentationTests': 2,
        'lifecycleFieldsObserved': all(key in lifecycle for key in required_lifecycle_fields),
    },
    'instrumentationPassed': 'OK (2 tests)' in instrumentation,
    'instrumentationFailurePresent': 'FAILURES!!!' in instrumentation,
}
(root / 'p5-summary.json').write_text(json.dumps(result, indent=2, ensure_ascii=False) + '\n')


def artifact(name):
    path = root / name
    return {'path': name, 'sha256': digest_bytes(path.read_bytes())} if path.is_file() else None


verifier_sha = digest_bytes(Path(__file__).read_bytes())
identity_ready = all(config.get(key) is not None for key in ('taskSetSha', 'gitCommit', 'dirty', 'sourceManifestSha'))
dataset_sha = config.get('taskSetSha')
environment = {
    'api': device.get('api'),
    'abi': device.get('abi'),
    'fingerprint': device.get('fingerprint'),
    'memoryMb': emulator.get('memoryMb'),
    'cores': emulator.get('cores'),
    'contextTokens': result['contextTokens'],
    'threads': result['threads'],
    'sampling': 'greedy',
}
common_identity = None
if identity_ready:
    common_identity = {
        'gitCommit': config['gitCommit'],
        'dirty': config['dirty'],
        'sourceManifestSha': config['sourceManifestSha'],
        'datasetSha': dataset_sha,
        'verifierSha': verifier_sha,
        'evidenceKind': 'real-provider',
        'measurementScope': 'api36-local-model-emulator',
        'provider': 'on-device-local',
        'model': config['key'],
        'modelAssetSha': config.get('sha'),
        'modelAssetBytes': config.get('size'),
        'protocol': 'binder+jni',
        'flavor': 'consumer',
        'api': device.get('api'),
        'device': device.get('avd'),
        'appApkSha': artifacts.get('app'),
        'testApkSha': artifacts.get('test'),
        'environmentSha': digest_bytes(canonical(environment)),
        'sessionConfigHash': digest_bytes(canonical({'mode': 'ACT', 'reasoning': 'OFF', 'contextTokens': 4096})),
        'toolSurfaceHash': digest_bytes(canonical(['read', 'write'])),
        'providerCapabilitySnapshotHash': digest_bytes(
            canonical(
                {
                    'text': True,
                    'tools': True,
                    'vision': False,
                    'contextTokens': 4096,
                    'source': 'MANUAL',
                }
            )
        ),
    }


def envelope(case_id, prompt, passed, facts, wall_ms, input_tokens, output_tokens, artifact_names, category=None):
    if common_identity is None:
        raise RuntimeError('comparable identity is unavailable for this legacy in-flight run')
    artifacts_for_case = [item for item in (artifact(name) for name in artifact_names) if item]
    total_tokens = None
    if input_tokens is not None and output_tokens is not None:
        total_tokens = input_tokens + output_tokens
    return {
        'schemaVersion': 1,
        'identity': {
            **common_identity,
            'suite': 'local-model-p5',
            'caseId': case_id,
            'promptSha': digest_bytes(prompt.encode()),
        },
        'outcome': {
            'verifiedResult': 'PASS' if passed else 'FAIL',
            'verifierFacts': facts,
            'artifacts': artifacts_for_case,
            'failureCategory': None if passed else category,
            'contributingCauses': [],
        },
        'trajectory': {
            'turns': 1 if case_id == 'file-aggregation' else None,
            'successorTurns': 0 if case_id == 'file-aggregation' else None,
            'modelCalls': len(aggregation_calls) if case_id == 'file-aggregation' else 1,
            'toolCalls': len(truth['persistedTools']) if case_id == 'file-aggregation' else 0,
            'invalidToolCalls': None,
            'repeatedFailures': None,
            'approvalsRequired': None,
            'approvalsAnswered': None,
            'humanInterventions': 0,
            'unknownEffects': None,
            'reviewsResolved': None,
            'compactions': None,
            'recoveryEvents': 0,
            'firstPassSucceeded': passed,
        },
        'cost': {
            'wallMillis': wall_ms,
            'inputTokens': input_tokens,
            'outputTokens': output_tokens,
            'totalTokens': total_tokens,
        },
    }


no_tool_input = no_tool.get('inputTokens') if no_tool else None
no_tool_output = no_tool.get('outputTokens') if no_tool else None
capability = calls[1] if len(calls) > 1 else None
capability_oracle = bool(
    capability and
    'echo' in capability.get('tools', []) and
    (capability.get('terminal') or '').startswith('Completed(')
)
aggregation_input = sum(call.get('inputTokens') or 0 for call in aggregation_calls) if aggregation_calls else None
required_lifecycle = required_lifecycle_fields
lifecycle_oracle = bool(
    lifecycle and
    all(isinstance(lifecycle.get(key), int) and lifecycle[key] >= 0 for key in required_lifecycle) and
    lifecycle['cancelToExitMs'] < 10_000 and
    lifecycle['terminateMs'] < 7_000
)
if identity_ready:
    envelopes = [
        envelope(
            'no-tool-text',
            'Reply with the single word: ok',
            no_tool_oracle,
            {'textExact': no_tool_oracle, 'nativeTimingObservability': result['nativeTimingObservability']},
            no_tool.get('wallMs') if no_tool else None,
            no_tool_input,
            no_tool_output,
            ['events.txt', 'p5-summary.json'],
            'MODEL_REASONING',
        ),
        envelope(
            'tool-capability',
            'Call the echo tool with text=probe',
            capability_oracle,
            {
                'echoToolCallObserved': capability_oracle,
                'terminal': capability.get('terminal') if capability else None,
                'tools': capability.get('tools') if capability else [],
                'nativeTimingObservability': result['nativeTimingObservability'],
            },
            capability.get('wallMs') if capability else None,
            capability.get('inputTokens') if capability else None,
            capability.get('outputTokens') if capability else None,
            ['events.txt', 'probe.txt', 'p5-summary.json'],
            'TOOL_SELECTION',
        ),
        envelope(
            'file-aggregation',
            'LocalModelRealTaskDeviceTest orders.csv + returns.csv by-region aggregation',
            aggregation_verified,
            {
                **result['aggregation']['oracle'],
                'turnState': truth['turnState'],
                'turnError': truth['turnError'],
                'sampledMemory': result['memory']['aggregationTask'],
                'nativeTimingObservability': result['nativeTimingObservability'],
            },
            result['aggregation']['taskWallMsObserved'],
            aggregation_input,
            aggregation_output if aggregation_calls else None,
            ['trajectory.txt', 'totals.csv', 'report.md', 'events.txt', 'runtime-memory.txt', 'p5-summary.json'],
            (
                'BUDGET'
                if aggregation_output_oracle['passed'] and not aggregation_durable_completed
                else 'MODEL_REASONING'
            ),
        ),
        envelope(
            'cancel-exit-lifecycle',
            'Cancel one active bounded local generation, then unload, reload and terminate the runtime',
            lifecycle_oracle,
            {
                'lifecycle': lifecycle,
                'allRequiredLatenciesObserved': all(key in lifecycle for key in required_lifecycle),
            },
            lifecycle.get('cancelToExitMs'),
            None,
            None,
            ['p5-lifecycle.json', 'baseline-phases.tsv', 'runtime-memory.txt', 'p5-summary.json'],
            'RECOVERY',
        ),
    ]
    (root / 'agent-eval-envelopes.json').write_text(json.dumps(envelopes, indent=2, ensure_ascii=False) + '\n')
else:
    result['comparisonIdentity'] = {'status': 'INCOMPLETE', 'reason': 'run started before P5 identity capture'}
    (root / 'p5-summary.json').write_text(json.dumps(result, indent=2, ensure_ascii=False) + '\n')
print(json.dumps(result, indent=2, ensure_ascii=False))
