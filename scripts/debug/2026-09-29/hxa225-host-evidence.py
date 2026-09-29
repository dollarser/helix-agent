#!/usr/bin/env python3
"""Collect bounded host evidence for HXA-225; never contacts a device or model."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def collect() -> dict:
    suites = {}
    selected = []
    patterns = ('ToolImage', 'ToolVisual', 'ToolVision', 'ViewImage', 'VerifiedImage', 'SubscriptionImage')
    roots = [Path('app'), Path('core'), Path('tools'), Path('provider'), Path('runtime')]
    result_dirs = []
    for root in roots:
        # Module trees are shallow; do not recursively walk compiled artifacts or caches.
        candidates = [root / 'build/test-results']
        candidates += list(root.glob('*/build/test-results'))
        for directory in candidates:
            if directory.is_dir():
                result_dirs.extend(p for p in directory.iterdir() if p.is_dir())
    for directory in sorted(set(result_dirs)):
        total = dict(tests=0, failures=0, errors=0, skipped=0)
        count = 0
        for file in sorted(directory.glob('TEST-*.xml')):
            node = ET.parse(file).getroot()
            values = {key: int(node.get(key, '0')) for key in total}
            for key, value in values.items():
                total[key] += value
            count += 1
            name = node.get('name', file.stem)
            if any(part in name for part in patterns):
                selected.append(dict(suite=str(directory), name=name, timestamp=node.get('timestamp'), **values))
        if count:
            total['passed'] = total['tests'] - total['failures'] - total['errors'] - total['skipped']
            suites[str(directory)] = total
    schema = Path('core/storage/src/androidTest/assets/com.helix.core.storage.HelixDatabase/1.json')
    database = json.loads(schema.read_text())['database']
    tables = [entity['tableName'] for entity in database['entities']]
    artifacts = []
    for file in sorted(Path('app/build/outputs/apk').glob('**/*.apk')):
        artifacts.append(dict(path=str(file), sha256=digest(file), bytes=file.stat().st_size))
    names = subprocess.check_output(['git', 'ls-files', '-c', '-o', '--exclude-standard'], text=True).splitlines()
    sources = {}
    for name in sorted(set(names)):
        path = Path(name)
        if path.is_file() and (path.suffix in {'.kt', '.kts', '.xml', '.lockfile'} or name == str(schema)):
            sources[name] = digest(path)
    fingerprint = hashlib.sha256(json.dumps(sources, sort_keys=True).encode()).hexdigest()
    return dict(
        head=subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
        source_fingerprint=fingerprint, source_file_count=len(sources), suites=suites, selected_tests=selected,
        schema=dict(sha256=digest(schema), version=database['version'], table_count=len(tables),
                    composer_drafts_present='composer_drafts' in tables),
        artifacts=artifacts, device='not requested', real_model='not requested',
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = collect()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    summary = {key: value for key, value in result.items() if key not in {'suites', 'selected_tests'}}
    summary['primary_suites'] = {key: value for key, value in result['suites'].items()
                               if key.startswith(('app/', 'core/storage/', 'tools/files/', 'tools/framework/', 'runtime/cli-client/'))}
    summary['selected_tests'] = result['selected_tests']
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
