#!/usr/bin/env python3
"""Summarize one archived Android JUnit attempt; reject missing or inconsistent results."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET


def summarize(paths):
    cases = {}
    for path in paths:
        root = ET.parse(path).getroot()
        suites = [node for node in root.iter() if node.tag.rsplit('}', 1)[-1] == 'testsuite']
        if not suites:
            raise ValueError(f'No test suites: {path}')
        for suite in suites:
            nodes = [n for n in suite if n.tag.rsplit('}', 1)[-1] == 'testcase']
            if not nodes:
                if any(n.tag.rsplit('}', 1)[-1] == 'testsuite' for n in suite):
                    continue
                raise ValueError(f'No executed case records: {path}')
            counts = dict(tests=len(nodes), failures=0, errors=0, skipped=0)
            for node in nodes:
                tags = {n.tag.rsplit('}', 1)[-1] for n in node}
                states = tags & {'failure', 'error', 'skipped'}
                if len(states) > 1:
                    raise ValueError(f'Ambiguous case status: {path}')
                state = next(iter(states), 'passed')
                key = (node.get('classname', suite.get('name')), node.get('name'))
                if not all(key) or key in cases:
                    raise ValueError(f'Missing or duplicate case identity: {key}')
                cases[key] = state
                if state != 'passed':
                    counts[{'failure': 'failures', 'error': 'errors', 'skipped': 'skipped'}[state]] += 1
            for key, count in counts.items():
                if suite.get(key) is not None and int(suite.get(key)) != count:
                    raise ValueError(f'Inconsistent {key} in {path}: declared {suite.get(key)}, observed {count}')
    if not cases:
        raise ValueError('No test cases; cannot report PASS')
    totals = {'tests': len(cases), 'passed': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
    for state in cases.values():
        totals[{'failure': 'failures', 'error': 'errors'}.get(state, state)] += 1
    verdict = 'FAIL' if totals['failures'] or totals['errors'] else 'INCOMPLETE' if totals['skipped'] else 'PASS'
    return {'status': verdict, **totals, 'cases': [{'class': k[0], 'method': k[1], 'status': v} for k, v in sorted(cases.items())]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('xml', nargs='+', type=Path, help='Explicit archived files from one module/device/attempt only')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error('output already exists; use a new attempt')
    try:
        result = summarize(args.xml)
    except (OSError, ET.ParseError, ValueError) as error:
        result = {'status': 'INVALID_EVIDENCE', 'error': str(error)}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('x') as output:
        json.dump(result, output, indent=2)
    return 0 if result['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
