#!/usr/bin/env python3
"""Prepare private connector inputs and run strictly separate offline / public MCP gates."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_INPUT = ROOT / 'scripts/local/fixtures/connectors'
OFFLINE = {
    'ConnectorSuppliedArchiveTest': 'inspectProductionReaderAndSkillImporter',
    'WorkBuddySuppliedArchiveTest': 'marketplaceArchivesPreserveAllSkillsAndReferences',
    'ConnectorExternalAcceptanceTest': 'pinnedPublicSourceConfigsUseProductionReader',
}
ONLINE = {'ConnectorExternalAcceptanceTest': 'publicDocumentationServiceNegotiatesAndAnswersReadOnlyQuery'}
ENV_KEYS = ('HELIX_CONNECTOR_SAMPLE_ZIP', 'HELIX_WORKBUDDY_SAMPLE_DIR',
            'HELIX_CONNECTOR_ACCEPTANCE_DIR', 'HELIX_PUBLIC_MCP_ACCEPTANCE')


def public_fetcher():
    spec = importlib.util.spec_from_file_location('public_configs', ROOT / 'scripts/fetch-hxa-125-samples.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def prepare(args):
    """Copy only packages, never the WorkBuddy account/config export or extracted scripts."""
    fetcher = public_fetcher()
    sources = {'qwenwork/qwenwork.zip': args.qwenwork}
    sources.update({f'workbuddy/{name}.zip': args.workbuddy / f'{name}.zip'
                    for name in ('github', 'kling-ai-plugin')})
    sources.update({f'public-configs/{name}': args.public_configs / name for name in fetcher.SOURCES})
    # Validate the entire input before changing the fixture directory.
    records = []
    for relative, source in sources.items():
        if not source.is_file() or source.stat().st_size > 32 * 1024 * 1024:
            raise ValueError(f'Missing or oversized sample: {relative}')
        sha = digest(source)
        if relative.endswith('.zip'):
            with zipfile.ZipFile(source) as archive:
                if sum(entry.file_size for entry in archive.infolist()) > 64 * 1024 * 1024:
                    raise ValueError(f'Expanded sample exceeds limit: {relative}')
                if archive.testzip() is not None:
                    raise ValueError(f'Invalid archive CRC: {relative}')
            origin = 'owner-supplied archive; import-only, no account or execution acceptance'
        else:
            name = source.name
            if sha != fetcher.EXPECTED_SHA256[name]:
                raise ValueError(f'Pinned public config hash mismatch: {relative}')
            json.loads(source.read_bytes())
            repo, commit, path = fetcher.SOURCES[name]
            origin = f'https://github.com/{repo}/blob/{commit}/{path}'
        destination = args.root / relative
        if destination.exists() and digest(destination) != sha:
            raise ValueError(f'Refusing to overwrite a different fixture: {relative}')
        records.append({'path': relative, 'sha256': sha, 'source': origin})
    args.root.mkdir(parents=True, exist_ok=True, mode=0o700)
    for relative, source in sources.items():
        destination = args.root / relative
        destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        if source.resolve() != destination.resolve():
            shutil.copyfile(source, destination)
        destination.chmod(0o600)
    manifest = args.root / 'manifest.json'
    manifest.write_text(json.dumps({'format': 'helix.connector-test-inputs.v1', 'files': records}, indent=2) + '\n')
    manifest.chmod(0o600)
    print(f'Prepared {len(records)} verified inputs in {args.root}; no package code executed')


def verified_inputs(root):
    manifest = json.loads((root / 'manifest.json').read_text())
    if manifest.get('format') != 'helix.connector-test-inputs.v1':
        raise ValueError('Unsupported sample manifest')
    expected = {'qwenwork/qwenwork.zip', 'workbuddy/github.zip', 'workbuddy/kling-ai-plugin.zip'}
    expected.update(f'public-configs/{name}' for name in public_fetcher().SOURCES)
    records = manifest['files']
    if len(records) != len(expected) or {row['path'] for row in records} != expected:
        raise ValueError('Incomplete or duplicate sample manifest')
    for row in records:
        if digest(root / row['path']) != row['sha256']:
            raise ValueError(f'Sample changed: {row["path"]}')
    return records


def inspect_reports(report_root, selected):
    results = []
    for cls, method in selected.items():
        suite = ET.parse(report_root / f'TEST-com.helix.app.connector.{cls}.xml').getroot()
        cases = suite.findall('testcase')
        if len(cases) != 1 or cases[0].get('name') != method:
            raise ValueError(f'Unexpected or empty execution: {cls}')
        if any(cases[0].find(tag) is not None for tag in ('failure', 'error', 'skipped')):
            raise ValueError(f'Failed or skipped: {cls}.{method}')
        results.append(f'{cls}.{method}')
    return results


def test(args):
    env = {key: value for key, value in os.environ.items() if key not in ENV_KEYS}
    if args.mode == 'offline':
        inputs = verified_inputs(args.root)
        env.update(dict(zip(ENV_KEYS[:3], map(str, (args.root / 'qwenwork/qwenwork.zip',
                                                  args.root / 'workbuddy', args.root / 'public-configs')))))
        selected = OFFLINE
    else:
        inputs = []
        env['HELIX_PUBLIC_MCP_ACCEPTANCE'] = 'true'
        selected = ONLINE
    # A fresh evidence directory prevents a previous pass being mistaken for this run.
    args.output.mkdir(parents=True, exist_ok=False)
    (args.output / 'inputs.json').write_text(json.dumps(inputs, indent=2) + '\n')
    for flavor in (('consumer', 'developer') if args.flavor == 'both' else (args.flavor,)):
        task = f'test{flavor.title()}DebugUnitTest'
        command = [str(ROOT / 'gradlew'), f':app:{task}', '--rerun']
        for cls, method in selected.items():
            command += ['--tests', f'com.helix.app.connector.{cls}.{method}']
        with (args.output / f'{flavor}.log').open('w') as log:
            status = subprocess.run(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT).returncode
        reports = ROOT / 'app/build/test-results' / task
        dest = args.output / flavor
        dest.mkdir()
        for cls in selected:
            source = reports / f'TEST-com.helix.app.connector.{cls}.xml'
            if source.is_file():
                shutil.copyfile(source, dest / source.name)
        if status:
            raise RuntimeError(f'{flavor} Gradle failed ({status}); see {args.output / (flavor + ".log")}')
        passed = inspect_reports(dest, selected)
        (dest / 'result.json').write_text(json.dumps({'mode': args.mode, 'passed': passed, 'skipped': 0}, indent=2))
        print(f'{args.mode} {flavor}: {len(passed)} passed, 0 skipped', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=DEFAULT_INPUT)
    commands = parser.add_subparsers(dest='command', required=True)
    setup = commands.add_parser('prepare', help='Copy verified local inputs; never downloads or executes them')
    setup.add_argument('--qwenwork', type=Path, required=True)
    setup.add_argument('--workbuddy', type=Path, required=True, help='Prepared directory with the two marketplace ZIPs')
    setup.add_argument('--public-configs', type=Path, required=True)
    run = commands.add_parser('test')
    run.add_argument('mode', choices=['offline', 'public-mcp'])
    run.add_argument('--flavor', choices=['consumer', 'developer', 'both'], default='both')
    run.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.root = args.root.resolve()
    if args.command == 'prepare':
        prepare(args)
    else:
        test(args)


if __name__ == '__main__':
    main()
