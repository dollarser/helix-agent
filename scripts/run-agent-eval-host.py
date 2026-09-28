#!/usr/bin/env python3
"""Run fresh, bounded host assertions and archive a reproducible HXA-227 baseline.

No device or provider access. Host boundary PASS is not a device trajectory PASS.
"""
import argparse
from pathlib import Path
import platform
import shutil
import subprocess
import time
from agent_eval import (aggregate, artifact, digest, fingerprint, markdown, normalize_junit,
                        read_json, source_manifest, write_json)

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ['app/src', 'core', 'provider', 'tools', 'runtime', 'feature', 'gradle', 'buildSrc',
           'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradlew', 'scripts/agent_eval.py',
           'scripts/run-agent-eval.py', 'scripts/run-agent-eval-host.py', 'evals/trajectory']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--manifest', type=Path, default=ROOT / 'evals/trajectory/host-boundaries.json')
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    manifest_path = args.manifest.resolve()
    manifest = read_json(manifest_path)
    source = source_manifest(ROOT, SOURCES)
    write_json(output / 'source-manifest.json', source)
    classes = sorted({ref['class'] for case in manifest['cases'] for ref in case['tests']})
    modules = {'app': ':app:testConsumerDebugUnitTest', 'core/storage': ':core:storage:testDebugUnitTest', 'core/agent': ':core:agent:test', 'core/workspace': ':core:workspace:test'}
    groups = {module: [name for name in classes if name.startswith('com.helix.' + module.replace('/', '.') + '.')] for module in modules}
    command = [str(ROOT / 'gradlew')]
    for module, task in modules.items():
        if not groups[module]:
            continue
        command.append(task)
        for name in groups[module]:
            command += ['--tests', name]
    command.append('--rerun-tasks')
    started = time.time()
    with (output / 'gradle.log').open('w') as log:
        result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
    observed = []
    for module, names in groups.items():
        variant = 'testConsumerDebugUnitTest' if module == 'app' else 'testDebugUnitTest' if module == 'core/storage' else 'test'
        for name in names:
            path = ROOT / module / 'build/test-results' / variant / ('TEST-' + name + '.xml')
            if path.exists() and path.stat().st_mtime >= started:
                target = output / path.name
                shutil.copyfile(path, target)
                observed.append(target)
    environment = {'os': platform.system(), 'machine': platform.machine(),
                   'java': subprocess.run(['java', '-version'], capture_output=True, text=True, check=True).stderr.strip()}
    test_sources = []
    for name in classes:
        relative = name.replace('.', '/') + '.kt'
        matches = [p for p in ROOT.glob('**/src/test/kotlin/' + relative) if '/build/' not in str(p)]
        if len(matches) != 1:
            raise ValueError('ambiguous verifier source: ' + name)
        test_sources.append({'class': name, 'sha256': digest(matches[0].read_bytes())})
    context = {'gitCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
               'dirty': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT)),
               'sourceManifestSha': source['sha256'], 'verifierSha': fingerprint(test_sources),
               'fixtureSha': fingerprint(manifest), 'environmentSha': fingerprint(environment),
               'sessionConfigHash': fingerprint({'scope': 'host-boundary'}),
               'toolSurfaceHash': fingerprint({'scope': 'no-tool-surface'}),
               'providerCapabilitySnapshotHash': fingerprint({'scope': 'no-provider'}),
               'flavor': 'consumer-host', 'api': 0, 'device': 'host-jvm',
               'provider': 'not-applicable', 'model': 'not-applicable', 'protocol': 'not-applicable', 'providerVersion': 'not-applicable'}
    write_json(output / 'context.json', context)
    records = normalize_junit(manifest, observed, context, output)
    unchanged = source_manifest(ROOT, SOURCES)['sha256'] == source['sha256']
    if result.returncode != 0 or not unchanged:
        for value in records:
            value['outcome']['verifiedResult'] = 'INVALID'
            value['outcome']['failureCategory'] = 'VERIFIER'
            value['outcome']['validationIssues'].append('host gate failed or sources changed during run')
    write_json(output / 'envelopes.json', records)
    report = aggregate(records)
    write_json(output / 'summary.json', report)
    (output / 'summary.md').write_text(markdown(report))
    write_json(output / 'run.json', {'command': command[1:], 'exitCode': result.returncode,
                                   'sourceUnchanged': unchanged, 'environment': environment,
                                   'artifacts': [artifact(path, output) for path in observed]})
    return 0 if all(v['outcome']['verifiedResult'] == 'PASS' for v in records) else 1


if __name__ == '__main__':
    raise SystemExit(main())
