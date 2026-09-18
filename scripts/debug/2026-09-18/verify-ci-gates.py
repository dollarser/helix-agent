"""Check that splitting CI preserves required Gradle tasks, lock checks and failures."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[3]
required = {
    'spotlessCheck', 'detekt', 'test', 'lintDebug', 'lintRelease', 'lintConsumerDebug',
    'lintDeveloperDebug', 'lintConsumerRelease', 'lintDeveloperRelease',
    ':app:assembleConsumerDebug', ':app:assembleDeveloperDebug',
    ':runtime:proot-app:assembleDebug', ':runtime:cli-app:assembleDebug',
    ':app:assembleConsumerRelease', ':app:assembleDeveloperRelease',
    ':runtime:proot-app:assembleRelease', ':runtime:cli-app:assembleRelease',
}
with tempfile.TemporaryDirectory(prefix='helix-ci-contract-') as directory:
    fixture = Path(directory)
    scripts = fixture / 'scripts'
    scripts.mkdir()
    for name in ['check-all.sh', 'ci-run-gate.sh']:
        shutil.copy2(root / 'scripts' / name, scripts / name)
    gradle = fixture / 'gradlew'
    gradle.write_text('''#!/usr/bin/env python3
import json, os, sys
with open(os.environ['GATE_CALLS'], 'a') as log:
    log.write(json.dumps(sys.argv[1:]) + '\\n')
print('fixture Gradle invocation', *sys.argv[1:])
sys.exit(17 if os.environ.get('FAIL_TASK') in sys.argv[1:] else 0)
''')
    gradle.chmod(0o755)
    locks = scripts / 'check-lockfiles.sh'
    locks.write_text('#!/usr/bin/env bash\nprintf \'["locks"]\\n\' >> "$GATE_CALLS"\nexit "${LOCK_EXIT:-0}"\n')
    locks.chmod(0o755)
    calls = fixture / 'calls.jsonl'
    env = dict(os.environ, GATE_CALLS=str(calls), GITHUB_STEP_SUMMARY=str(fixture / 'summary.md'))

    def run(script, option, extra=None):
        return subprocess.run(['bash', str(scripts / script), option], cwd=fixture,
                              env=dict(env, **(extra or {})), capture_output=True, text=True)

    def recorded():
        result = [json.loads(line) for line in calls.read_text().splitlines()]
        calls.unlink()
        return result

    assert run('check-all.sh', '--build').returncode == 0
    complete = recorded()
    assert {task for call in complete[:-1] for task in call} == required
    assert complete[-1] == ['locks']
    assert run('check-all.sh', '--analysis').returncode == 0
    assert run('check-all.sh', '--tests-build').returncode == 0
    assert recorded() == complete, 'Local --build and parallel CI must execute the same contract'
    assert run('ci-run-gate.sh', '--analysis', {'FAIL_TASK': 'lintRelease'}).returncode == 17
    assert (fixture / 'build/ci/analysis.log').is_file()
    assert (fixture / 'build/ci/analysis.tsv').read_text().strip().endswith('\t17')
    recorded()
    assert run('ci-run-gate.sh', '--tests-build', {'LOCK_EXIT': '19'}).returncode == 19
    assert recorded()[-1] == ['locks']
    assert run('ci-run-gate.sh', '--unknown').returncode == 2
    print('PASS: gate union, local/CI parity, Gradle failure, lock failure, logs, timing, invalid mode')
