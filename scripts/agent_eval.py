"""Versioned offline Agent evaluation evidence. No runtime, telemetry or device access."""
from collections import Counter, defaultdict
from copy import deepcopy
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

VERSION = 1
RESULTS = {'PASS', 'FAIL', 'BLOCKED', 'INVALID'}
KINDS = {'fixture', 'fake-provider', 'real-provider'}
CATEGORIES = set('MODEL_REASONING TOOL_SELECTION TOOL_ARGUMENTS TOOL_EXECUTION PERMISSION_OR_APPROVAL ENVIRONMENT CONTEXT_LOSS RECOVERY UNKNOWN_EFFECT BUDGET PROVIDER_PROTOCOL VERIFIER HARNESS_INVARIANT'.split())
COUNTS = 'turns successorTurns modelCalls toolCalls invalidToolCalls repeatedFailures approvalsRequired approvalsAnswered humanInterventions unknownEffects reviewsResolved compactions recoveryEvents'.split()
METRICS = COUNTS + ['firstPassSucceeded']
HASH_FIELDS = 'datasetSha promptSha verifierSha fixtureSha environmentSha sessionConfigHash toolSurfaceHash providerCapabilitySnapshotHash'.split()
# Code/artifact identities intentionally differ between baseline and candidate.
CONTROLS = HASH_FIELDS + ['flavor', 'api', 'device', 'provider', 'model', 'protocol', 'providerVersion', 'evidenceKind', 'measurementScope']
TREATMENTS = set('promptSha sessionConfigHash toolSurfaceHash providerCapabilitySnapshotHash provider model protocol providerVersion'.split())
GROUP_FIELDS = 'evidenceKind measurementScope suite flavor api device provider model protocol providerVersion datasetSha environmentSha sessionConfigHash toolSurfaceHash providerCapabilitySnapshotHash sourceManifestSha'.split()
SHA = re.compile(r'[0-9a-f]{64}\Z')


class EvidenceError(ValueError):
    pass


def digest(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False, allow_nan=False).encode()


def fingerprint(value):
    return digest(canonical(value))


def read_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8'), parse_constant=lambda x: (_ for _ in ()).throw(EvidenceError('non-finite JSON')))


def write_json(path, value):
    Path(path).write_text(json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False) + '\n', encoding='utf-8')


def source_manifest(root, paths):
    """Explicit source roots only; include relevant untracked files, no ignored outputs."""
    root = Path(root).resolve()
    if not paths or any(Path(p).is_absolute() or '..' in Path(p).parts for p in paths):
        raise EvidenceError('explicit repository-relative source paths required')
    names = subprocess.check_output(['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard', '--', *paths], cwd=root).decode().split('\0')
    entries = []
    for name in sorted(set(filter(None, names))):
        path = root / name
        if path.is_symlink():
            raise EvidenceError('source symlink requires explicit provenance handling: ' + name)
        entries.append({'path': name, 'sha256': digest(path.read_bytes()) if path.is_file() else None})
    if not entries:
        raise EvidenceError('empty source manifest')
    return {'version': 1, 'entries': entries, 'sha256': fingerprint(entries)}


def envelope(identity, result, facts, artifacts, metrics=None, cost=None, category=None):
    return {'schemaVersion': VERSION, 'identity': identity,
            'outcome': {'verifiedResult': result, 'verifierFacts': facts, 'artifacts': artifacts,
                        'failureCategory': category, 'contributingCauses': []},
            'trajectory': {**dict.fromkeys(METRICS), **(metrics or {})},
            'cost': {'wallMillis': None, 'inputTokens': None, 'outputTokens': None, 'totalTokens': None, **(cost or {})}}


def artifact(path, root):
    path, root = Path(path).resolve(), Path(root).resolve()
    try:
        relative = path.relative_to(root).as_posix()
    except ValueError as error:
        raise EvidenceError('artifact outside evidence root') from error
    return {'path': relative, 'sha256': digest(path.read_bytes())}


def validate(record, root):
    """Malformed identity/verifier => INVALID; version/hash tampering => reject input."""
    value = deepcopy(record)
    if type(value.get('schemaVersion')) is not int or value.get('schemaVersion') != VERSION:
        raise EvidenceError('unsupported schemaVersion')
    identity, outcome = value.get('identity', {}), value.get('outcome', {})
    if not isinstance(identity, dict) or not isinstance(outcome, dict):
        raise EvidenceError('identity/outcome must be objects')
    issues = []
    for key in ['suite', 'caseId', 'gitCommit', 'measurementScope', 'evidenceKind']:
        if not isinstance(identity.get(key), str) or not identity[key].strip():
            issues.append('missing identity.' + key)
    if identity.get('evidenceKind') not in KINDS:
        issues.append('invalid evidenceKind')
    if type(identity.get('dirty')) is not bool:
        issues.append('missing dirty identity')
    for key in ['datasetSha', 'promptSha', 'verifierSha', 'sourceManifestSha']:
        if not SHA.fullmatch(str(identity.get(key, ''))):
            issues.append('missing/invalid identity.' + key)
    for key in HASH_FIELDS + ['appApkSha', 'testApkSha']:
        if identity.get(key) is not None and not SHA.fullmatch(str(identity[key])):
            issues.append('invalid hash: ' + key)
    if outcome.get('verifiedResult') not in RESULTS:
        issues.append('invalid verifiedResult')
    if not isinstance(outcome.get('verifierFacts'), dict) or not outcome['verifierFacts']:
        issues.append('missing verifier facts')
    category = outcome.get('failureCategory')
    if category is not None and category not in CATEGORIES:
        issues.append('invalid failureCategory')
    if any(c not in CATEGORIES for c in outcome.get('contributingCauses', [])):
        issues.append('invalid contributing cause')
    artifacts = outcome.get('artifacts')
    if not isinstance(artifacts, list) or not artifacts:
        issues.append('missing verifier artifacts')
    else:
        for entry in artifacts:
            relative = entry.get('path')
            if not isinstance(relative, str) or Path(relative).is_absolute() or '..' in Path(relative).parts:
                raise EvidenceError('unsafe artifact path')
            path = (Path(root) / relative).resolve()
            if not path.is_relative_to(Path(root).resolve()) or not path.is_file():
                raise EvidenceError('missing/outside artifact: ' + relative)
            if digest(path.read_bytes()) != entry.get('sha256'):
                raise EvidenceError('artifact hash mismatch: ' + relative)
    if not isinstance(value.get('trajectory'), dict) or not isinstance(value.get('cost'), dict):
        raise EvidenceError('trajectory/cost must be objects')
    metrics = value['trajectory']
    for key in METRICS:
        item = metrics.setdefault(key, None)
        if item is None:
            continue
        if key == 'firstPassSucceeded':
            if type(item) is not bool:
                issues.append('invalid boolean metric')
        elif type(item) is not int or item < 0:
            issues.append('invalid count: ' + key)
    if metrics.get('firstPassSucceeded') is True and outcome.get('verifiedResult') != 'PASS':
        issues.append('first pass requires verified PASS')
    for key in ['wallMillis', 'inputTokens', 'outputTokens', 'totalTokens']:
        item = value.setdefault('cost', {}).setdefault(key, None)
        valid_type = type(item) in (int, float) if key == 'wallMillis' else type(item) is int
        if item is not None and (not valid_type or not math.isfinite(item) or item < 0):
            issues.append('invalid cost: ' + key)
    if issues:
        outcome['verifiedResult'] = 'INVALID'
        outcome['failureCategory'] = 'VERIFIER'
    outcome['validationIssues'] = issues
    value['outcome'] = outcome
    return value


def normalize_m10(raw_path, context, root):
    """Three existing producers; preserve raw evidence, do not copy text/args/secrets."""
    raw = read_json(raw_path)
    if context.get('gitCommit') and context['gitCommit'] != raw.get('gitCommit'):
        raise EvidenceError('fixed producer/context source identity mismatch')
    prefix = str(raw.get('id', '')).split('-')[0]
    suites = {'file': 'files', 'browser': 'browser', 'goal': 'goal'}
    if prefix not in suites:
        raise EvidenceError('unsupported fixed suite')
    identity = {**context, 'suite': suites[prefix], 'caseId': raw.get('id'),
                'gitCommit': raw.get('gitCommit'), 'datasetSha': raw.get('datasetSha256'),
                'promptSha': raw.get('promptSha256'), 'fixtureSha': raw.get('fixtureContextSha256'),
                'provider': raw.get('provider'), 'model': raw.get('model'),
                'protocol': raw.get('protocol'), 'providerVersion': raw.get('providerReportedVersion'),
                'api': raw.get('api'), 'device': raw.get('device'),
                'measurementScope': 'session' if 'trajectoryMetrics' in raw else 'last-turn'}
    # Session metrics are captured by the test adapter after its deterministic verifier.
    metrics = raw.get('trajectoryMetrics', {})
    if not metrics and isinstance(raw.get('calls'), list):
        metrics = {'toolCalls': len(raw['calls'])}
    facts = {k: raw[k] for k in ('turnState', 'goalState', 'runOutcome', 'approvalBlocked', 'writeOccurred') if k in raw}
    facts['producerResult'] = raw.get('result')
    if not raw.get('turnState'):
        facts = {}
    value = envelope(identity, raw.get('result'), facts, [artifact(raw_path, root)], metrics,
                     {'wallMillis': raw.get('elapsedMs')}, raw.get('failureCategory'))
    value['outcome']['contributingCauses'] = raw.get('contributingCauses', [])
    if 'trajectoryMetrics' in raw:
        # Legacy elapsedMs measures only the last Turn, not the whole session.
        value['cost']['wallMillis'] = None
    return validate(value, root)


def junit_cases(xml_paths):
    records = {}
    for path in xml_paths:
        tree = ET.parse(path)
        for case in tree.iter('testcase'):
            key = (case.get('classname'), case.get('name'))
            if key in records:
                raise EvidenceError('duplicate JUnit case: ' + str(key))
            state = 'FAIL' if case.find('failure') is not None or case.find('error') is not None else 'BLOCKED' if case.find('skipped') is not None else 'PASS'
            records[key] = (state, float(case.get('time', '0')) * 1000, path)
    return records


def normalize_junit(manifest, xml_paths, context, root):
    if manifest.get('schemaVersion') != VERSION or not manifest.get('cases'):
        raise EvidenceError('unsupported or empty suite manifest')
    observed = junit_cases(xml_paths)
    result = []
    for spec in manifest['cases']:
        if not spec.get('tests'):
            raise EvidenceError('case has no verifier selectors')
        selected = [observed.get((ref['class'], ref['method'])) for ref in spec['tests']]
        states = [item[0] if item else 'INVALID' for item in selected]
        state = next((s for s in ('INVALID', 'FAIL', 'BLOCKED') if s in states), 'PASS')
        facts = {'assertions': [{'class': ref['class'], 'method': ref['method'], 'result': state_} for ref, state_ in zip(spec['tests'], states)], 'coverage': manifest['coverage']}
        identity = {**context, 'suite': manifest['suite'], 'caseId': spec['id'],
                    'datasetSha': fingerprint(manifest), 'promptSha': fingerprint(spec),
                    'measurementScope': manifest['coverage'], 'evidenceKind': manifest['evidenceKind']}
        files = sorted(set(str(item[2]) for item in selected if item))
        value = envelope(identity, state, facts, [artifact(path, root) for path in files],
                         cost={'wallMillis': sum(item[1] for item in selected if item) if all(selected) else None},
                         category='HARNESS_INVARIANT' if state == 'FAIL' else 'VERIFIER' if state == 'INVALID' else None)
        result.append(validate(value, root))
    return result


def unique(records):
    seen = set()
    for value in records:
        identity = value['identity']
        key = canonical({k: identity.get(k) for k in GROUP_FIELDS + ['caseId']})
        if key in seen:
            raise EvidenceError('duplicate case in run: ' + str(key))
        seen.add(key)


def statistics(records):
    counts = Counter(r['outcome']['verifiedResult'] for r in records)
    eligible = [r for r in records if r['outcome']['verifiedResult'] in ('PASS', 'FAIL')]
    metrics = {}
    for key in METRICS:
        values = [r['trajectory'].get(key) for r in eligible if r['trajectory'].get(key) is not None]
        metrics[key] = {'known': len(values), 'eligible': len(eligible), 'mean': sum(values) / len(values) if values else None}
    recovered = [r for r in eligible if r['trajectory'].get('recoveryEvents') is not None and r['trajectory']['recoveryEvents'] > 0]
    rates = {}
    for name, numerator, denominator in (
        ('taskSuccess', counts['PASS'], len(eligible)),
        ('recoverySuccess', sum(r['outcome']['verifiedResult'] == 'PASS' for r in recovered), len(recovered)),
    ):
        rates[name] = {'numerator': numerator, 'denominator': denominator, 'rate': numerator / denominator if denominator else None}
    for key in ('invalidToolCalls', 'repeatedFailures', 'humanInterventions', 'unknownEffects'):
        known = [r['trajectory'][key] for r in eligible if r['trajectory'].get(key) is not None]
        rates[key + 'Incidence'] = {'numerator': sum(v > 0 for v in known), 'denominator': len(known), 'rate': sum(v > 0 for v in known) / len(known) if known else None}
    costs = {}
    for key in ('wallMillis', 'inputTokens', 'outputTokens', 'totalTokens'):
        values = [r['cost'].get(key) for r in eligible if r['cost'].get(key) is not None]
        costs[key] = {'known': len(values), 'mean': sum(values) / len(values) if values else None}
    return {'count': len(records), 'outcomes': {k: counts[k] for k in sorted(RESULTS)}, 'rates': rates, 'metrics': metrics, 'cost': costs,
            'failureCategories': dict(Counter(r['outcome'].get('failureCategory') or 'UNATTRIBUTED' for r in records if r['outcome']['verifiedResult'] == 'FAIL'))}


def aggregate(records):
    unique(records)
    groups = defaultdict(list)
    for value in records:
        i = value['identity']
        # Never pool synthetic and real providers, host boundary and device trajectory evidence.
        key = canonical({k: i.get(k) for k in GROUP_FIELDS})
        groups[key].append(value)
    return {'schemaVersion': VERSION, 'totalCases': len(records), 'groups': [
        {'identity': json.loads(key), **statistics(values)}
        for key, values in sorted(groups.items(), key=lambda item: str(item[0]))]}


def compare(baseline, candidate, treatments):
    unique(baseline)
    unique(candidate)
    if set(treatments) - TREATMENTS:
        raise EvidenceError('unsupported treatment fields')
    def keyed(values):
        result = {}
        for value in values:
            key = (value['identity']['suite'], value['identity']['caseId'])
            if key in result:
                raise EvidenceError('comparison requires one cohort per case')
            result[key] = value
        return result
    before, after = keyed(baseline), keyed(candidate)
    reasons, pairs = [], []
    if not before or before.keys() != after.keys():
        reasons.append('case set mismatch or empty run')
    for key in sorted(before.keys() & after.keys()):
        a, b = before[key], after[key]
        for field in CONTROLS:
            av, bv = a['identity'].get(field), b['identity'].get(field)
            if av is None or bv is None or av == 'unknown' or bv == 'unknown' or av == 'UNAVAILABLE' or bv == 'UNAVAILABLE':
                reasons.append(f'{key}: unknown control {field}')
            elif field in treatments:
                if treatments[field] != {'before': av, 'after': bv}:
                    reasons.append(f'{key}: treatment value mismatch {field}')
            elif av != bv:
                reasons.append(f'{key}: control mismatch {field}')
        if any(r['outcome']['verifiedResult'] in ('INVALID', 'BLOCKED') for r in (a, b)):
            reasons.append(f'{key}: incomplete evidence')
        pairs.append({'suite': key[0], 'caseId': key[1], 'before': a['outcome']['verifiedResult'], 'after': b['outcome']['verifiedResult']})
    deltas = None
    if not reasons:
        deltas = {'taskSuccess': statistics(candidate)['rates']['taskSuccess']['rate'] - statistics(baseline)['rates']['taskSuccess']['rate'],
                  'metrics': {}, 'cost': {}}
        for section, keys in (('metrics', METRICS), ('cost', ('wallMillis', 'inputTokens', 'outputTokens', 'totalTokens'))):
            src = 'trajectory' if section == 'metrics' else 'cost'
            for field in keys:
                matched = [(before[k][src].get(field), after[k][src].get(field)) for k in before]
                known = [(a, b) for a, b in matched if a is not None and b is not None]
                deltas[section][field] = {'paired': len(known), 'meanDelta': sum(b-a for a, b in known)/len(known) if known else None}
    return {'schemaVersion': VERSION, 'comparable': not reasons, 'reasons': reasons, 'treatments': treatments, 'pairs': pairs, 'delta': deltas}


def markdown(report):
    lines = ['# Agent Eval report', '', 'Unknown metrics remain unknown; synthetic and real-provider results are separate.', '']
    for group in report['groups']:
        title = ' / '.join(str(group['identity'].get(k)) for k in ('suite', 'evidenceKind', 'measurementScope', 'flavor', 'model', 'protocol'))
        lines += ['## ' + title, '', 'Identity: `' + json.dumps(group['identity'], sort_keys=True) + '`', '',
                  'Outcomes: ' + json.dumps(group['outcomes'], sort_keys=True), '',
                  '| Metric | Known | Mean |', '| --- | --- | --- |']
        for key, value in group['metrics'].items():
            lines.append(f"| {key} | {value['known']}/{value['eligible']} | {value['mean'] if value['mean'] is not None else 'unknown'} |")
        lines += ['', 'Rates: `' + json.dumps(group['rates'], sort_keys=True) + '`', '',
                  'Measured cost: `' + json.dumps(group['cost'], sort_keys=True) + '`', '']
    return '\n'.join(lines) + '\n'
