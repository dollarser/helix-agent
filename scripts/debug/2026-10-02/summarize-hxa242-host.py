#!/usr/bin/env python3
"""Summarize existing HXA-242 host evidence; never run devices or change project sources."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def identity(path):
    with path.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    return {'path': str(path.relative_to(ROOT)), 'bytes': path.stat().st_size, 'sha256': digest}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host-run', required=True)
    parser.add_argument('--source-log', required=True)
    parser.add_argument('--artifact-log', required=True)
    args = parser.parse_args()
    host_run = ROOT / args.host_run
    logs = [host_run / name for name in ('format.log', 'host.log', 'lockfiles.log')]
    logs += [ROOT / args.source_log, ROOT / args.artifact_log]
    host = logs[1].read_text()
    require('BUILD SUCCESSFUL' in host and 'BUILD FAILED' not in host, 'Host build did not pass')
    source = logs[3].read_text()
    for phrase in ('Documentation verification passed', 'Internationalization verification passed', 'Secret scan passed'):
        require(phrase in source, 'Source evidence incomplete: ' + phrase)
    artifact = logs[4].read_text()
    for phrase in ('consumer debug:', 'developer debug:', 'Variant boundary verification passed', 'HTTP/TLS configuration verified'):
        require(phrase in artifact, 'APK evidence incomplete: ' + phrase)
    targets = {
        'CleartextWarningTest', 'OkHttpWireClientTest', 'ProviderDraftDiscoveryTest',
        'ProviderComposerTest', 'ProviderStoresTest', 'ProviderFormValidationTest',
        'ScrollIndicatorsTest', 'ComposerFeedbackTest', 'DraftRestorationFeedbackTest',
        'AutomationTargetInputTest', 'AutomationPlatformActionTest',
        'AutomationNodeActionExecutorTest', 'AutomationToolsTest', 'ProotGuestMediaPermissionsTest',
    }
    roots = (
        'provider/api/build/test-results/test',
        'app/build/test-results/testConsumerDebugUnitTest',
        'app/build/test-results/testDeveloperDebugUnitTest',
        'tools/automation/build/test-results/testDebugUnitTest',
        'runtime/proot-app/build/test-results/testDebugUnitTest',
    )
    suites, methods, seen = [], set(), set()
    for directory in roots:
        for path in sorted((ROOT / directory).glob('TEST-*.xml')):
            root = ET.parse(path).getroot()
            name = root.get('name', '')
            short = name.rsplit('.', 1)[-1]
            if short not in targets:
                continue
            seen.add(short)
            cases = root.findall('testcase')
            require(cases and len(cases) == int(root.get('tests')), 'Missing testcase evidence: ' + name)
            require(all(case.find('failure') is None and case.find('error') is None and case.find('skipped') is None for case in cases), 'Non-passing regression: ' + name)
            methods.update((case.get('classname', name), case.get('name')) for case in cases)
            suites.append({'suite': name, 'executions': len(cases), 'evidence': identity(path)})
    require(seen == targets, 'Missing test suites: ' + repr(targets - seen))
    apks = []
    for flavor in ('consumer', 'developer'):
        apks.append(identity(ROOT / f'app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk'))
        apks.append(identity(ROOT / f'app/build/outputs/apk/androidTest/{flavor}/debug/app-{flavor}-debug-androidTest.apk'))
    changed = subprocess.check_output(['git', 'diff', '--name-only', '-z'], cwd=ROOT).decode().split('\0')
    untracked = subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard', '-z'], cwd=ROOT).decode().split('\0')
    inputs = {}
    for name in sorted(set(changed + untracked)):
        if name.startswith(('app/', 'core/', 'provider/', 'tools/', 'runtime/')) and Path(name).suffix in ('.kt', '.kts', '.xml', '.md'):
            path = ROOT / name
            inputs[name] = identity(path) if path.is_file() else {'deleted': True}
    result = {
        'status': 'passed',
        'scope': 'HXA-242 host-only: warning-only HTTP, UI feedback and automation effect truth',
        'head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        'branch': subprocess.check_output(['git', 'branch', '--show-current'], cwd=ROOT, text=True).strip(),
        'task_summary': re.findall(r'^.*actionable tasks:.*$', host, re.M)[-1],
        'logs': [identity(path) for path in logs],
        'selected_regression_suites': suites,
        'selected_unique_methods': len(methods),
        'selected_executions': sum(suite['executions'] for suite in suites),
        'http_policy_python_tests': 6,
        'changed_code_inputs': inputs,
        'apks': apks,
        'device_execution': 'not requested',
        'limits': ['No emulator or physical-device execution', 'No real external accounts', 'Debug APK acceptance, not release/store acceptance'],
    }
    output = ROOT / 'build/hxa242/host-acceptance.json'
    temporary = output.with_suffix('.tmp')
    temporary.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    temporary.replace(output)
    print(json.dumps({key: value for key, value in result.items() if key not in ('selected_regression_suites', 'changed_code_inputs')}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
