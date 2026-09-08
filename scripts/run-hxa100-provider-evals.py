#!/usr/bin/env python3
"""Run one fixed M10 suite through Android adapters and production tool dispatch.

Each result names its exact case IDs; a passing subset does not imply all 45 passed.
JavaScript cancellation uses the host Stop action after a real model starts the fixture.
"""
import atexit
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import subprocess
import urllib.error
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('serial')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--provider-port', type=int, default=30008)
    parser.add_argument('--suite', choices=('providers', 'files', 'javascript', 'plan', 'browser', 'skills', 'mcp', 'a2a', 'accessibility', 'root', 'goal'), default='providers')
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        parser.error('explicit emulator serial is required')
    if not 1024 <= args.provider_port <= 65535:
        parser.error('provider port must be in 1024..65535')
    args.output.mkdir(parents=True, exist_ok=False)
    try:
        with urllib.request.urlopen(f'http://127.0.0.1:{args.provider_port}/v1/models', timeout=10) as response:
            model = json.load(response)['data'][0]['id']
    except (TimeoutError, urllib.error.URLError, ValueError, KeyError, IndexError) as error:
        summary = {'scope': args.suite, 'passed': False, 'count': 0,
                   'stage': 'provider_discovery', 'errorType': type(error).__name__,
                   'dateUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                   'datasetSha256': hashlib.sha256(Path('evals/m10/fixed-evals.tsv').read_bytes()).hexdigest()}
        (args.output / 'result.json').write_text(json.dumps(summary, indent=2))
        print(json.dumps(summary))
        return 1
    config = {'endpoint': f'http://10.0.2.2:{args.provider_port}/v1', 'model': model,
              'gitCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
              'dateUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'providerReportedVersion': 'UNAVAILABLE', 'provider': 'local-sglang'}
    mcp_fixture = None
    a2a_fixture = None
    if args.suite == 'mcp':
        from hxa100_mcp_fixture import McpFixture
        mcp_fixture = McpFixture(args.output / 'mcp-server-events.json')
        atexit.register(mcp_fixture.close)
        config.update(mcpFixtureEndpoint=mcp_fixture.endpoint, executionBackend='synthetic_local_mcp_server')
    if args.suite == 'a2a':
        from hxa100_a2a_fixture import A2aFixture
        a2a_fixture = A2aFixture(args.output / 'a2a-server-events.json')
        atexit.register(a2a_fixture.close)
        config.update(a2aFixtureEndpoint=a2a_fixture.endpoint, executionBackend='synthetic_local_a2a_server')
    fixtures = [args.output / 'config.json', Path('evals/m10/fixed-evals.tsv')]
    if args.suite == 'plan':
        source = 'https://www.rfc-editor.org/rfc/rfc8259.txt'
        with urllib.request.urlopen(source, timeout=30) as response:
            spec = response.read(1_000_001)
        if len(spec) > 1_000_000:
            raise ValueError('public specification exceeds fixture size bound')
        target = args.output / 'rfc8259.txt'
        target.write_bytes(spec)
        fixtures.append(target)
        config.update(publicSource=source, publicSourceSha256=hashlib.sha256(spec).hexdigest())
    (args.output / 'config.json').write_text(json.dumps(config, indent=2))
    adb = str(Path.home() / 'Library/Android/sdk/platform-tools/adb')
    prefix = [adb, '-s', args.serial]
    if a2a_fixture is not None:
        subprocess.run(prefix + ['reverse', f'tcp:{a2a_fixture.port}', f'tcp:{a2a_fixture.port}'], check=True)
    installed = {}
    for package in ('com.helix.agent.developer', 'com.helix.agent.developer.test'):
        paths = subprocess.check_output(prefix + ['shell', 'pm', 'path', package], text=True).splitlines()
        if not paths:
            raise RuntimeError('required APK is not installed: ' + package)
        installed[package] = [subprocess.check_output(prefix + ['shell', 'sha256sum', path.removeprefix('package:')], text=True).split()[0] for path in paths]
    config.update(installedApkSha256=installed,
                  runnerSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                  workingDiffSha256=hashlib.sha256(subprocess.check_output(['git', 'diff', 'HEAD'])).hexdigest())
    (args.output / 'config.json').write_text(json.dumps(config, indent=2))
    remote = 'files/hxa100'
    run_as = prefix + ['shell', 'run-as', 'com.helix.agent.developer']
    subprocess.run(run_as + ['mkdir', '-p', remote], check=True)
    for path in fixtures:
        subprocess.run(run_as + ['tee', remote + '/' + path.name], input=path.read_bytes(), check=True, stdout=subprocess.DEVNULL)
    case_prefixes = {'providers': ('chat-', 'provider-'), 'files': ('file-',), 'javascript': ('js-',), 'plan': ('plan-',), 'browser': ('browser-',), 'skills': ('skill-',), 'mcp': ('mcp-',), 'a2a': ('a2a-',), 'accessibility': ('accessibility-',), 'root': ('root-',), 'goal': ('goal-',)}[args.suite]
    ids = [line.split('\t')[0] for line in Path('evals/m10/fixed-evals.tsv').read_text().splitlines() if line.startswith(case_prefixes)]
    for case_id in ids:
        subprocess.run(run_as + ['rm', '-f', remote + '/' + case_id + '.json'], check=True)
    test_class = {'providers': 'FixedProviderEvaluationDeviceTest', 'files': 'FixedFileEvaluationDeviceTest', 'javascript': 'FixedJavascriptEvaluationDeviceTest', 'plan': 'FixedPlanEvaluationDeviceTest', 'browser': 'FixedBrowserEvaluationDeviceTest', 'skills': 'FixedSkillEvaluationDeviceTest', 'mcp': 'FixedMcpEvaluationDeviceTest', 'a2a': 'FixedA2aEvaluationDeviceTest', 'accessibility': 'FixedAccessibilityEvaluationDeviceTest', 'root': 'FixedRootEvaluationDeviceTest', 'goal': 'FixedGoalEvaluationDeviceTest'}[args.suite]
    command = prefix + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'com.helix.app.eval.' + test_class, '-e', 'helix.eval', 'true', '-e', 'helix.eval.providerPort', str(args.provider_port), 'com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner']
    with (args.output / 'instrumentation.log').open('w') as log:
        try:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=2400)
        finally:
            if mcp_fixture is not None:
                mcp_fixture.close()
                atexit.unregister(mcp_fixture.close)
            if a2a_fixture is not None:
                a2a_fixture.close()
                atexit.unregister(a2a_fixture.close)
                subprocess.run(prefix + ['reverse', '--remove', f'tcp:{a2a_fixture.port}'], check=True)
    device = args.output / 'device'
    device.mkdir()
    for case_id in ids:
        record = subprocess.run(run_as + ['cat', remote + '/' + case_id + '.json'], capture_output=True)
        if record.returncode == 0:
            value = json.loads(record.stdout)
            value.update(schemaVersion=1, dataset='fixed-evals.tsv',
                         evidence=str(args.output / 'instrumentation.log'),
                         installedApkSha256=installed, runnerSha256=config['runnerSha256'],
                         workingDiffSha256=config['workingDiffSha256'])
            (device / (case_id + '.json')).write_text(json.dumps(value, indent=2))
    records = [json.loads(path.read_text()) for path in (args.output / 'device').glob('*.json') if path.name != 'config.json']
    log = (args.output / 'instrumentation.log').read_text()
    passed = result.returncode == 0 and 'OK (1 test)' in log and len(records) == len(ids) and {r.get('id') for r in records} == set(ids) and all(r.get('result') == 'PASS' for r in records)
    passed = passed and not any('INSTRUMENTATION_STATUS_CODE: ' + str(code) in log for code in (-1, -2, -3, -4))
    execution_counts = None
    if args.suite == 'mcp':
        events = json.loads((args.output / 'mcp-server-events.json').read_text())
        execution_counts = {case: sum(event.get('event') == 'tool_start' and event.get('case') == case for event in events) for case in ids}
        passed = passed and execution_counts == {'mcp-001': 0, 'mcp-002': 1, 'mcp-003': 1, 'mcp-004': 1}
    if args.suite == 'a2a':
        events = json.loads((args.output / 'a2a-server-events.json').read_text())
        execution_counts = {case: sum(e['method'] == 'SendMessage' and e['case'] == case for e in events) for case in ids}
        passed = passed and execution_counts == {'a2a-001': 0, 'a2a-002': 1, 'a2a-003': 1, 'a2a-004': 1}
        passed = passed and sum(e['method'] == 'GetTask' and e['case'] == 'a2a-003' for e in events) >= 2
    summary = {'scope': args.suite, 'expectedIds': ids, 'passed': passed,
               'count': len(records), 'datasetSha256': hashlib.sha256(Path('evals/m10/fixed-evals.tsv').read_bytes()).hexdigest()}
    if execution_counts is not None:
        summary['executionCounts'] = execution_counts
    (args.output / 'result.json').write_text(json.dumps(summary, indent=2))
    print(json.dumps(summary))
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(main())
