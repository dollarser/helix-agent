#!/usr/bin/env python3
"""Final HXA-241 ordinary-class matrix inside one existing owned emulator lifecycle."""
from pathlib import Path
import hashlib
import importlib.util
import json
import os
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main():
    serial, destination = sys.argv[1:]
    output = Path(destination).resolve()
    output.relative_to((ROOT / 'build/hxa241').resolve())
    owner = json.loads((output / 'owner.json').read_text())
    if owner['serial'] != serial or not serial.startswith('emulator-'):
        raise ValueError('Only the current owned emulator may be used')
    support = load('hxa241_suite', HERE / 'hxa241-emulator-acceptance.py')
    verifier = load('hxa241_verifier', HERE / 'summarize-hxa241-device.py')
    support.SERIAL = serial
    support.OUT = output / 'classes'
    support.OUT.mkdir()
    requested = ['com.helix.app.' + name for name in support.CLASSES]
    reports = []
    for flavor in ('consumer', 'developer'):
        os.kill(owner['pid'], 0)
        _, sources = verifier.matrix.methods_for(requested, flavor)
        previous = set(support.OUT.iterdir())
        support.suite(flavor, [])
        created = set(support.OUT.iterdir()) - previous
        if len(created) != 1:
            raise RuntimeError('Ambiguous suite result directory')
        report = verifier.summarize(created.pop())
        for path, expected in sources.items():
            if hashlib.sha256((ROOT / path).read_bytes()).hexdigest() != expected:
                raise RuntimeError('Test source changed while executing: ' + path)
        if report['sources'] != sources:
            raise RuntimeError('Evidence source identity mismatch')
        reports.append({key: report[key] for key in ('flavor', 'class_count', 'method_count', 'passed', 'failed', 'skipped', 'directory', 'apk_identity', 'method_ids', 'sources')})
        (output / 'ordinary-results.json').write_text(json.dumps(reports, indent=2) + '\n')
    recovery = load('hxa241_phases', HERE / 'hxa241-recovery-acceptance.py')
    recovery.support.SERIAL = serial
    recovery.support.OUT = output / 'recoveries'
    recovery.support.OUT.mkdir()
    phase_reports = []
    original_argv = sys.argv
    try:
        for flavor, storage in (('consumer', False), ('developer', False), ('developer', True)):
            os.kill(owner['pid'], 0)
            previous = set(recovery.support.OUT.iterdir())
            sys.argv = [str(HERE / 'hxa241-recovery-acceptance.py'), '--flavor', flavor] + (['--storage'] if storage else [])
            recovery.main()
            created = set(recovery.support.OUT.iterdir()) - previous
            if len(created) != 1:
                raise RuntimeError('Ambiguous recovery result directory')
            directory = created.pop()
            results = json.loads((directory / 'results.json').read_text())
            if len(results) != (4 if storage else 13) or any(row['status'] != 'passed' for row in results):
                raise RuntimeError('Recovery cutpoint matrix is incomplete')
            phase_reports.append({'flavor': flavor, 'storage': storage, 'results': results, 'directory': str(directory.relative_to(ROOT))})
            (output / 'recovery-results.json').write_text(json.dumps(phase_reports, indent=2) + '\n')
    finally:
        sys.argv = original_argv
    print(json.dumps({'matrix': [{key: row[key] for key in ('flavor', 'class_count', 'method_count', 'passed', 'failed', 'skipped')} for row in reports],
                      'distinct_methods': len(set().union(*(set(row['method_ids']) for row in reports)))}, indent=2), flush=True)


if __name__ == '__main__':
    main()
