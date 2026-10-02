#!/usr/bin/env python3
"""HXA-241 read-only evidence reconciliation; reuses existing parsers and never accesses a device.

Only the final JSON summary is written. Historical failed runs and raw logs remain unchanged.
"""
from pathlib import Path
import hashlib
import importlib.util
import json
import os
import re
import sys

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'build/hxa241/emulator-20261002'
sys.path.insert(0, str(ROOT / 'scripts'))
from instrumentation_junit import parse
from owned_acceptance import collect_owned, test_records

EVIDENCE = {}


def require(ok, message):
    if not ok:
        raise ValueError(message)


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def text(path):
    data = path.read_bytes()
    EVIDENCE[str(path.relative_to(ROOT))] = hashlib.sha256(data).hexdigest()
    return data.decode('utf-8')


def load(path):
    return json.loads(text(path))


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def passing(raw_path, expected):
    records = parse(text(raw_path))
    methods = {cls + '#' + method for cls, method in records}
    require(methods == set(expected), 'Wrong method identity: ' + str(raw_path))
    require(all(code == 0 for code, _ in records.values()), 'Non-passing method: ' + str(raw_path))
    return methods


def main():
    freeze = module('hxa241_frozen_inputs', Path(__file__).with_name('freeze-hxa241-device-inputs.py'))
    frozen = load(OUT / 'final-source-identity.json')
    require(frozen == freeze.snapshot(), 'Source/APK inputs changed after acceptance freeze')
    expected_apks = {flavor: {kind: frozen['apks'][flavor + '-' + kind]['sha256'] for kind in ('app', 'test')}
                     for flavor in ('consumer', 'developer')}
    classes = set()
    matrix = module('hxa241_source_methods', ROOT / 'scripts/run-acceptance-matrix.py')
    final = OUT / 'final-matrix'
    owner = load(final / 'owner.json')
    closed = load(final / 'closed.json')
    require(closed == {'pid': owner['pid'], 'exit': 0}, 'Final matrix emulator did not close cleanly')
    ordinary = load(final / 'ordinary-results.json')
    require([row['flavor'] for row in ordinary] == ['consumer', 'developer'], 'Missing/duplicate ordinary flavor')
    ordinary_methods = set()
    for row in ordinary:
        flavor = row['flavor']
        directory = ROOT / row['directory']
        requested = load(directory / 'classes.json')['classes']
        require(len(requested) == len(set(requested)) == 18, 'Unexpected ordinary class set')
        expected, sources = matrix.methods_for(requested, flavor)
        require(row['sources'] == sources, 'Changed ordinary test sources')
        actual = set()
        for cls in requested:
            selected = [method for method in expected if method.split('#')[0] == cls]
            actual.update(passing(directory / 'logs' / (cls + '.log'), selected))
        require(actual == set(expected) == set(row['method_ids']), 'Ordinary method inventory mismatch')
        require(len(actual) == 86 and (row['passed'], row['failed'], row['skipped']) == (86, 0, 0), 'Ordinary counts changed')
        require({kind: row['apk_identity'][kind]['sha256'] for kind in ('app', 'test')} == expected_apks[flavor],
                'Ordinary evidence used an old APK')
        classes.update(requested)
        ordinary_methods.update(actual)

    cut_cases = {'chat.MemoryProcessRecoveryDeviceTest-setup', 'localmodel.ModelPublicationRecoveryDeviceTest-setup',
                 'export.SessionExportRecoveryDeviceTest-setup'}
    cut_cases.update('connector.ConnectorInstallRecoveryDeviceTest-' + cut for cut in ('preparing', 'before-commit', 'after-commit'))
    cut_cases.update('chat.WorkspaceProcessRecoveryDeviceTest-' + cut for cut in ('setup', 'setup-before-rename', 'setup-purging', 'setup-purged'))
    cut_cases.update('files.WorkspaceBackupRecoveryDeviceTest-' + cut for cut in ('setup-prepared', 'setup-deleted', 'setup-restoring'))
    storage_cases = {
        'ui.SharedStorageDeviceTest-grantedRootNavigationKeepsAgentScopeSeparate-granted',
        'ui.ManualSharedFileDeviceTest-userCanManageSharedFilesWithoutProviderAndDeleteRequiresConfirmation-granted',
        'ui.SharedStorageDeviceTest-revokingAppOpRemovesTheManualRootWithoutGrantingAgentAccess-granted',
        'ui.SharedStorageDeviceTest-revokingAppOpRemovesTheManualRootWithoutGrantingAgentAccess-revoked',
    }
    phases = load(final / 'recovery-results.json')
    require([(p['flavor'], p['storage']) for p in phases] == [('consumer', False), ('developer', False), ('developer', True)],
            'Missing/duplicate phase group')
    for group in phases:
        directory = ROOT / group['directory']
        rows = load(directory / 'results.json')
        require(rows == group['results'], 'Phase aggregate differs from source')
        expected = storage_cases if group['storage'] else cut_cases
        require(len(rows) == len(expected) and {r['case'] for r in rows} == expected, 'Incomplete phase matrix')
        apk = load(directory / 'apk-identity.json')
        require({k: apk[k]['sha256'] for k in ('app', 'test')} == expected_apks[group['flavor']], 'Old phase APK')
        for row in rows:
            require(row['status'] == 'passed', 'Failed phase remains')
            suffix = '' if group['storage'] else '-verify'
            methods = passing(directory / (row['case'] + suffix + '.log'), row['verified_methods'])
            classes.update(method.split('#')[0] for method in methods)
            if not group['storage']:
                setup = text(directory / (row['case'] + '-setup.log'))
                require('Process crashed' in setup and 'FAILURES!!!' not in setup, 'No expected setup death')

    groups = []
    device_pages = set()
    for name, count in [('composer-final', 2), ('input-recovery-final-build', 8), ('revision-final-build', 2)]:
        directory = OUT / name
        batches = load(directory / 'batches.json')
        require(len(batches) == count and len({b['batch'] for b in batches}) == count, 'Missing/duplicate normal recovery batches')
        for batch in batches:
            label = batch['batch']
            flavor = label.split('-')[0]
            target = directory / label
            expected = load(directory / (label + '-expected.json'))
            for path, digest in expected['sources'].items():
                require(sha(ROOT / path) == digest, 'Recovery source changed: ' + path)
            report = load(directory / (label + '-report.json'))
            fresh = collect_owned(target, expected['methods'])
            require(fresh['device_lifecycle']['closed']['exit'] == 0, 'Normal-recovery emulator did not close cleanly')
            device = load(target / 'device.json')
            require(device['api'] == 36 and device['kind'] == 'owned-emulator', 'Wrong recovery device')
            device_pages.add(device['pageSize'])
            require(batch['exit'] == 0 and fresh['verdict'] == report['verdict'] == 'DEVICE_BATCH_PASS', 'Incomplete owned recovery')
            require(fresh['artifacts'] == report['artifacts'] == expected_apks[flavor], 'Old or mismatched normal recovery APK')
            require(fresh['counts'] == report['counts'] and fresh['source_refs'] == report['source_refs'], 'Recovery evidence changed')
            normal = load(target / 'normal-process.json')
            require(normal == report['normalProcess'] and normal['beforePid'] != normal['afterPid'] and normal['normalActivity'],
                    'Missing actual normal-process death/reopen evidence')
            records = test_records(text(target / 'verify-logcat.txt'))
            require(records == report['verifyMethods'] and all(r['status'] == 'passed' and r.get('finished') for r in records.values()),
                    'Room recovery verification did not pass')
            classes.update(method.split('#')[0] for method in records)
        groups.append({'group': name, 'passed_scenarios': count, 'failed': 0, 'skipped': 0})

    local = OUT / 'first-success-dependency-guidance'
    local_owner, local_closed = load(local / 'owner.json'), load(local / 'closed.json')
    require(local_closed == {'pid': local_owner['pid'], 'exit': 0}, 'Local-model emulator did not close cleanly')
    require(load(local / 'artifacts.json') == expected_apks['developer'], 'Local-model evidence used an old APK')
    for kind in ('app', 'test'):
        require(sha(local / (kind + '.apk')) == expected_apks['developer'][kind], 'Changed local-model copied APK')
    method = 'com.helix.app.localmodel.FirstSuccessJourneyDeviceTest#firstSuccessSurvivesProcessDeathWithoutReexecution'
    passing(local / 'instrumentation.txt', [method])
    classes.add(method.split('#')[0])
    trace = text(local / 'first-success-evidence.txt')
    for required in ('tools=[write:COMPLETED, read:COMPLETED]', 'batches=[[write], [read]]',
                     'tools=2 modelCalls=3 messages=6', 'duplicateSideEffect=false',
                     'restoredModel=3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597'):
        require(required in trace, 'Missing strict local-model evidence: ' + required)
    setup, verify = trace.splitlines()
    first = dict(re.findall(r'(pid|session|turn|workspace|artifact)=([^\s]+)', setup))
    second = dict(re.findall(r'(pid|session|turn|workspace|artifact)=([^\s]+)', verify))
    require(first['pid'] != second['pid'] and all(first[k] == second[k] for k in ('session', 'turn', 'workspace', 'artifact')),
            'Local-model original identities changed or process was not restarted')
    require('Process crashed' in text(local / 'recovery-setup.txt'), 'Local model did not reach setup process death')
    require(len(classes) == 30, 'Historical failure-class coverage is incomplete')
    require(frozen == freeze.snapshot(), 'Acceptance inputs changed while checking results')
    summary = {'status': 'passed', 'scope': 'HXA-241 API36 ARM64 historical 30-class reconciliation only',
               'head': frozen['head'], 'apks': frozen['apks'], 'changed_code_files': len(frozen['changed_code']),
               'normal_recovery_page_sizes': sorted(device_pages),
               'ordinary': [{'flavor': r['flavor'], 'classes': 18, 'passed': 86, 'failed': 0, 'skipped': 0} for r in ordinary],
               'ordinary_unique_methods': len(ordinary_methods), 'covered_classes': sorted(classes),
               'recovery_cutpoints': {'consumer': 13, 'developer': 13}, 'developer_storage_phases': 4,
               'normal_recovery_groups': groups, 'local_model_first_success': {'flavor': 'developer', 'passed': 1,
               'model_sha256': '3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597',
               'model_bytes': 2497281120, 'duplicate_side_effect': False},
               'limits': ['Not the entire historical 782-test sweep', 'Not API29, physical OEM, true 16KiB devices or real subscriptions',
                          'Not HXA-240 FFmpeg execution acceptance', 'One local-model fixture, not general model-quality proof'],
               'evidence_sha256': EVIDENCE}
    destination = OUT / 'final-acceptance.json'
    temporary = destination.with_suffix('.json.partial')
    temporary.write_text(json.dumps(summary, indent=2, ensure_ascii=False) + '\n')
    os.replace(temporary, destination)
    print(json.dumps({k: v for k, v in summary.items() if k not in ('covered_classes', 'evidence_sha256', 'apks')},
                     indent=2, ensure_ascii=False))


if __name__ == '__main__':
    main()
