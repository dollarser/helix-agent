#!/usr/bin/env python3
"""Reconcile saved owned runs by actual methods, teardown and immutable APK identity; no device access."""
import argparse
import json
from pathlib import Path
from instrumentation_junit import require_passed


def summarize(directories, logs=('instrumentation.txt',)):
    if not directories or not logs:
        raise ValueError('At least one run and instrumentation log are required')
    rows = []
    identity = None
    for directory in directories:
        directory = Path(directory)
        artifacts = json.loads((directory / 'artifacts.json').read_text())
        owner = json.loads((directory / 'owner.json').read_text())
        closed = json.loads((directory / 'closed.json').read_text())
        if closed.get('exit') != 0 or closed.get('pid') != owner.get('pid') or not owner.get('pid'):
            raise ValueError(f'Owned teardown missing or mismatched: {directory.name}')
        if not artifacts.get('app') or not artifacts.get('test'):
            raise ValueError('Missing frozen APK identity')
        if identity is not None and artifacts != identity:
            raise ValueError('APK identity changed across runs; compare flavors separately')
        identity = artifacts
        outcomes = {name: len(require_passed((directory / name).read_text())) for name in logs}
        rows.append(dict(run=directory.name, tests=sum(outcomes.values()), logs=outcomes,
                         artifacts=artifacts, closed=closed))
    return dict(tests=sum(row['tests'] for row in rows), runs=rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directories', nargs='+', type=Path)
    parser.add_argument('--log', action='append', help='Repeat for additional raw instrumentation phases')
    parser.add_argument('--output', type=Path, required=True, help='Fresh summary path; existing evidence is preserved')
    args = parser.parse_args()
    try:
        result = summarize(args.directories, args.log or ('instrumentation.txt',))
        with args.output.open('x') as destination:
            json.dump(result, destination, indent=2)
            destination.write('\n')
    except (OSError, ValueError, KeyError) as failure:
        parser.exit(1, f'Owned result reconciliation failed: {failure}\n')
    print(f"Verified {len(result['runs'])} owned runs and {result['tests']} actual methods")


if __name__ == '__main__':
    main()
