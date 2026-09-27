#!/usr/bin/env python3
"""Offline HXA-227 evidence normalization/reporting. Never starts a device or model."""
import argparse
from pathlib import Path
import subprocess
import sys
from agent_eval import (EvidenceError, aggregate, compare, digest, fingerprint, markdown,
                        normalize_junit, normalize_m10, read_json, source_manifest, validate, write_json)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    provenance = sub.add_parser('provenance')
    provenance.add_argument('--source', nargs='+', required=True)
    provenance.add_argument('--output', type=Path, required=True)
    for name in ('fixed', 'junit'):
        cmd = sub.add_parser(name)
        cmd.add_argument('--context', type=Path, required=True)
        cmd.add_argument('--root', type=Path, required=True)
        cmd.add_argument('--output', type=Path, required=True)
        cmd.add_argument('--input', type=Path, nargs='+', required=True)
        if name == 'junit':
            cmd.add_argument('--manifest', type=Path, required=True)
    report = sub.add_parser('report')
    report.add_argument('--input', type=Path, required=True)
    report.add_argument('--root', type=Path, required=True)
    report.add_argument('--output', type=Path, required=True)
    delta = sub.add_parser('compare')
    delta.add_argument('--baseline', type=Path, required=True)
    delta.add_argument('--candidate', type=Path, required=True)
    delta.add_argument('--baseline-root', type=Path, required=True)
    delta.add_argument('--candidate-root', type=Path, required=True)
    delta.add_argument('--treatments', type=Path)
    delta.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'provenance':
        manifest = source_manifest('.', args.source)
        value = {'gitCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
                 'dirty': bool(subprocess.check_output(['git', 'status', '--porcelain'])),
                 'sourceManifestSha': manifest['sha256'], 'sourceManifest': manifest}
    elif args.command in ('fixed', 'junit'):
        context = read_json(args.context)
        if args.command == 'fixed':
            value = [normalize_m10(path, context, args.root) for path in args.input]
        else:
            value = normalize_junit(read_json(args.manifest), args.input, context, args.root)
        aggregate(value)  # Reject duplicate case identities before writing.
    elif args.command == 'report':
        value = aggregate([validate(r, args.root) for r in read_json(args.input)])
        args.output.with_suffix('.md').write_text(markdown(value), encoding='utf-8')
    else:
        value = compare([validate(r, args.baseline_root) for r in read_json(args.baseline)],
                        [validate(r, args.candidate_root) for r in read_json(args.candidate)],
                        read_json(args.treatments) if args.treatments else {})
    write_json(args.output, value)
    if args.command == 'compare':
        return 0 if value['comparable'] else 2
    if args.command == 'report':
        return 0 if value['totalCases'] and all(g['outcomes']['INVALID'] == 0 and g['outcomes']['FAIL'] == 0 and g['outcomes']['BLOCKED'] == 0 for g in value['groups']) else 1
    if isinstance(value, list):
        return 0 if value and all(v['outcome']['verifiedResult'] == 'PASS' for v in value) else 1
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (EvidenceError, ValueError, KeyError, TypeError, OSError) as error:
        print(f'Evidence rejected: {error}', file=sys.stderr)
        raise SystemExit(2)
