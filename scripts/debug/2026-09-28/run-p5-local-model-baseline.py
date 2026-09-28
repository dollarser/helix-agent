#!/usr/bin/env python3
"""Run the P5 4B baseline under one controlled API36 consumer configuration."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[3]
TASK_SET = ROOT / 'evals/local-model/p5-baseline-v1.json'
SOURCE_PATHS = [
    'app/src/main/cpp',
    'app/src/main/kotlin/com/helix/app/agent',
    'app/src/main/kotlin/com/helix/app/chat',
    'app/src/main/kotlin/com/helix/app/localmodel',
    'app/src/main/kotlin/com/helix/app/provider',
    'app/src/androidTest/kotlin/com/helix/app/localmodel',
    'core/agent/src/main',
    'core/model/src/main',
    'core/policy/src/main',
    'core/storage/src/main',
    'core/workspace/src/main',
    'provider/api/src/main',
    'tools/files/src/main',
    'tools/framework/src/main',
    'evals/local-model',
    'scripts/agent_eval.py',
    'scripts/debug/2026-09-28/run-real-model-followup.py',
    'scripts/debug/2026-09-28/run-p5-local-model-baseline.py',
    'scripts/debug/2026-09-28/summarize-p5-local-model.py',
]
MODELS = (
    {
        'key': 'qwen3-4b-instruct-2507-q4-k-m',
        'path': 'build/hxa222-closeout/Qwen3-4B-Instruct-2507-Q4_K_M.gguf',
        'sha': '3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597',
        'size': 2497281120,
    },
)


def source_manifest():
    names = subprocess.check_output(
        ['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard', '--', *SOURCE_PATHS],
        cwd=ROOT,
    ).decode().split('\0')
    entries = []
    for name in sorted(set(filter(None, names))):
        path = ROOT / name
        if not path.is_file() or path.is_symlink():
            raise RuntimeError('P5 source manifest requires ordinary files: ' + name)
        entries.append({'path': name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
    canonical = json.dumps(entries, sort_keys=True, separators=(',', ':')).encode()
    return {'version': 1, 'entries': entries, 'sha256': hashlib.sha256(canonical).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', default='build/p5-local-model-baseline')
    parser.add_argument('--avd', default='Helix_HXA210_API36')
    parser.add_argument('--first-port', type=int, default=5668)
    parser.add_argument('--task-timeout-seconds', type=int, default=1800)
    args = parser.parse_args()
    output = ROOT / args.output
    output.mkdir(parents=True, exist_ok=False)
    task_set_sha = hashlib.sha256(TASK_SET.read_bytes()).hexdigest()
    git_commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    starting_source = source_manifest()
    source_manifest_sha = starting_source['sha256']
    (output / 'source-manifest.json').write_text(json.dumps(starting_source, indent=2) + '\n')
    dirty = bool(subprocess.check_output(['git', 'status', '--porcelain=v1'], cwd=ROOT, text=True).strip())
    run_identity = {
        'gitCommit': git_commit,
        'dirty': dirty,
        'sourceManifestSha': source_manifest_sha,
        'taskSetSha': task_set_sha,
        'contextTokens': 4096,
        'threads': 2,
        'sampling': 'greedy',
        'emulatorMemoryMb': 8192,
        'emulatorCores': 4,
        'densityDpi': 400,
        'loadStartState': 'fresh-app-data-runtime-unloaded',
    }
    (output / 'run-identity.json').write_text(json.dumps(run_identity, indent=2) + '\n')
    classes = ','.join((
        'com.helix.app.localmodel.LocalModelRealTaskDeviceTest',
        'com.helix.app.localmodel.LocalModelPerformanceLifecycleDeviceTest',
    ))
    reports = []
    for index, model in enumerate(MODELS):
        model_path = ROOT / model['path']
        assert model_path.is_file(), model_path
        target = output / model['key']
        env = dict(os.environ)
        env.update({
            'HXA222_MODEL_PATH': model['path'],
            'HXA222_MODEL_SHA': model['sha'],
            'HXA222_MODEL_SIZE': str(model['size']),
            'HXA222_CONTEXT': '4096',
            'HXA222_TASK_TIMEOUT_SECONDS': str(args.task_timeout_seconds),
            'HXA222_CLASSES': classes,
            'HXA222_BASELINE_IDLE_MS': '3000',
            'HXA222_MEMORY_SAMPLE_SECONDS': '1',
            'HXA222_CLEAR_APP_DATA': '1',
            'HXA222_REQUIRE_TEST_PASS': '0',
        })
        command = [
            sys.executable, 'scripts/run-owned-emulator.py',
            '--avd', args.avd,
            '--port', str(args.first_port + 2 * index),
            '--memory-mb', '8192',
            '--cores', '4',
            '--density-dpi', '400',
            '--apk', 'app/build/outputs/apk/consumer/debug/app-consumer-debug.apk',
            '--test-apk', 'app/build/outputs/apk/androidTest/consumer/debug/app-consumer-debug-androidTest.apk',
            '--runner', 'com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner',
            '--classes', 'com.helix.app.localmodel.LocalModelRuntimeDeviceTest',
            '--after-script', 'scripts/debug/2026-09-28/run-real-model-followup.py',
            '--output', str(target),
            '--timeout', str(args.task_timeout_seconds + 400),
            '--raw-results',
        ]
        result = subprocess.run(command, cwd=ROOT, env=env, check=False)
        (target / 'p5-config.json').write_text(
            json.dumps({**model, **run_identity, 'runnerExit': result.returncode}, indent=2) + '\n'
        )
        summary = subprocess.run(
            [sys.executable, 'scripts/debug/2026-09-28/summarize-p5-local-model.py', str(target)],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        )
        (target / 'p5-summary.stdout.json').write_text(summary.stdout)
        parsed = json.loads((target / 'p5-summary.json').read_text())
        reports.append(parsed)
        print(
            model['key'],
            'runnerExit=', result.returncode,
            'evidenceComplete=', parsed['evidenceCompleteness']['complete'],
            'aggregation=', parsed['aggregation']['oracle']['passed'],
            flush=True,
        )
    (output / 'combined-summary.json').write_text(json.dumps(reports, indent=2, ensure_ascii=False) + '\n')
    (output / 'task-set.json').write_bytes(TASK_SET.read_bytes())
    ending_source = source_manifest()
    (output / 'source-manifest-after.json').write_text(json.dumps(ending_source, indent=2) + '\n')
    if ending_source != starting_source:
        raise RuntimeError('P5 source changed during the baseline; raw evidence is retained but not comparable')
    # A quality failure is baseline DATA, not harness failure. Infrastructure/evidence must be complete for every model.
    assert len(reports) == len(MODELS)
    assert all(report['evidenceCompleteness']['complete'] for report in reports), reports


if __name__ == '__main__':
    main()
