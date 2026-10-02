#!/usr/bin/env python3
"""Collect HXA-243 host evidence, or recheck code/APK identities after a local commit."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
OUTPUT = ROOT / 'build/hxa243/host-acceptance.json'


def require(condition, reason):
    if not condition:
        raise RuntimeError(reason)


def identity(path):
    with path.open('rb') as stream:
        sha = hashlib.file_digest(stream, 'sha256').hexdigest()
    return {'path': str(path.relative_to(ROOT)), 'bytes': path.stat().st_size, 'sha256': sha}


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT, text=True)


def collect(host_run, source_log, artifact_log):
    host_dir = ROOT / host_run
    logs = [host_dir / name for name in ('format.log', 'host.log', 'lockfiles.log')]
    logs += [ROOT / source_log, ROOT / artifact_log]
    host = logs[1].read_text()
    require('BUILD SUCCESSFUL' in host and 'BUILD FAILED' not in host, 'Host checks did not pass')
    require('Dependency lock verification passed' in logs[2].read_text(), 'Dependency lock check missing')
    for text in ('Documentation verification passed', 'ADR verification passed',
                 'Internationalization verification passed', 'Secret scan passed', 'Ran 5 tests'):
        require(text in logs[3].read_text(), 'Source evidence missing: ' + text)
    for text in ('consumer debug:', 'developer debug:', 'Variant boundary verification passed',
                 'Mobile Use service, scope channel and screenshot/gesture declarations verified'):
        require(text in logs[4].read_text(), 'Actual APK evidence missing: ' + text)
    for task in (':app:assembleConsumerDebugAndroidTest', ':app:assembleDeveloperDebugAndroidTest',
                 ':tools:automation:assembleDebugAndroidTest', ':app:lintConsumerDebug', ':app:lintDeveloperDebug'):
        require(task in host, 'Required host task absent: ' + task)

    roots = {
        'tools/automation/build/test-results/testDebugUnitTest': None,
        'core/policy/build/test-results/test': {'UserScopeTest'},
        'app/build/test-results/testConsumerDebugUnitTest': {'WorkspaceToolImagePublisherTest'},
        'app/build/test-results/testDeveloperDebugUnitTest': {'WorkspaceToolImagePublisherTest'},
    }
    reports, methods, seen, executions = [], set(), set(), 0
    for directory, selected in roots.items():
        for path in sorted((ROOT / directory).glob('TEST-*.xml')):
            suite = ET.parse(path).getroot()
            name = suite.get('name', '')
            if selected is not None and name.rsplit('.', 1)[-1] not in selected:
                continue
            cases = suite.findall('testcase')
            require(cases and len(cases) == int(suite.get('tests', '-1')), 'Empty/incomplete suite: ' + name)
            for case in cases:
                require(all(case.find(tag) is None for tag in ('failure', 'error', 'skipped')), 'Nonpass: ' + name)
                methods.add((case.get('classname', name), case.get('name')))
            seen.add(name.rsplit('.', 1)[-1])
            executions += len(cases)
            reports.append({'suite': name, 'tests': len(cases), 'file': identity(path)})
    require({'AutomationDeviceContractTest', 'AutomationDeviceToolsTest', 'AutomationExpiryTest',
             'AutomationWaitConditionsTest', 'UserScopeTest', 'WorkspaceToolImagePublisherTest'} <= seen,
            'Missing required HXA-243 regression suite')
    app_totals = []
    for flavor in ('Consumer', 'Developer'):
        totals = dict(tests=0, failures=0, errors=0, skipped=0)
        skipped = []
        directory = ROOT / f'app/build/test-results/test{flavor}DebugUnitTest'
        for path in directory.glob('TEST-*.xml'):
            suite = ET.parse(path).getroot()
            for key in totals:
                totals[key] += int(suite.get(key, '0'))
            skipped.extend(f"{case.get('classname')}#{case.get('name')}"
                           for case in suite.findall('testcase') if case.find('skipped') is not None)
        require(totals['tests'] > 0 and totals['failures'] == totals['errors'] == 0, 'App unit failure: ' + flavor)
        app_totals.append({'flavor': flavor, **totals, 'skipped_cases': sorted(skipped)})

    apks = []
    for flavor in ('consumer', 'developer'):
        for pattern in (f'app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk',
                        f'app/build/outputs/apk/androidTest/{flavor}/debug/app-{flavor}-debug-androidTest.apk'):
            apks.append(identity(ROOT / pattern))
    module_tests = list((ROOT / 'tools/automation/build/outputs/apk/androidTest/debug').glob('*.apk'))
    require(len(module_tests) == 1, 'Expected exactly one automation AndroidTest APK')
    apks.append(identity(module_tests[0]))
    candidates = git('diff', '--name-only', '-z').split('\0')
    candidates += git('ls-files', '--others', '--exclude-standard', '-z').split('\0')
    inputs = {}
    for name in sorted(set(candidates)):
        if name.startswith(('app/', 'core/', 'tools/', 'extensions/')) and Path(name).suffix in ('.kt', '.kts', '.xml', '.json'):
            path = ROOT / name
            inputs[name] = identity(path) if path.is_file() else {'deleted': True}
    return {
        'status': 'passed', 'scope': 'HXA-243 host/debug artifacts, not device execution',
        'base_commit': git('rev-parse', 'HEAD').strip(), 'branch': git('branch', '--show-current').strip(),
        'task_summary': re.findall(r'^.*actionable tasks:.*$', host, re.M)[-1],
        'logs': [identity(path) for path in logs], 'selected_reports': reports,
        'selected_unique_methods': len(methods), 'selected_method_executions': executions,
        'app_unit_summaries': app_totals, 'mobile_use_python_regressions': 5,
        'apks': apks, 'code_inputs': inputs, 'device': 'not requested', 'external_accounts': 'not requested',
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host-run')
    parser.add_argument('--source-log')
    parser.add_argument('--artifact-log')
    parser.add_argument('--verify-inputs', action='store_true')
    args = parser.parse_args()
    if args.verify_inputs:
        data = json.loads(OUTPUT.read_text())
        for name, expected in data['code_inputs'].items():
            path = ROOT / name
            require(not path.exists() if expected.get('deleted') else identity(path) == expected, 'Changed source: ' + name)
        for expected in data['apks']:
            require(identity(ROOT / expected['path']) == expected, 'APK changed: ' + expected['path'])
        print('HXA-243 accepted source and five APK identities match the current checkout.')
        return
    require(all((args.host_run, args.source_log, args.artifact_log)), 'All evidence paths are required')
    result = collect(args.host_run, args.source_log, args.artifact_log)
    tmp = OUTPUT.with_suffix('.tmp')
    tmp.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    tmp.replace(OUTPUT)
    print(json.dumps({key: value for key, value in result.items()
                      if key not in ('selected_reports', 'code_inputs', 'logs', 'apks')}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
