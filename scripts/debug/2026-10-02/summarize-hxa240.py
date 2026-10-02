#!/usr/bin/env python3
"""Summarize exact current JVM reports and package evidence, not archived test results."""
from pathlib import Path
import datetime
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT/'build/hxa240'
DIRECTORIES = {
    'consumer': 'app/build/test-results/testConsumerDebugUnitTest',
    'developer': 'app/build/test-results/testDeveloperDebugUnitTest',
    'proot-core': 'runtime/proot-core/build/test-results/test',
    'proot-app': 'runtime/proot-app/build/test-results/testDebugUnitTest',
    'proot-client': 'runtime/proot-client/build/test-results/testDebugUnitTest',
    'proot-ipc': 'runtime/proot-ipc/build/test-results/testDebugUnitTest',
}
NEW = {'com.helix.app.proot.LinuxMediaTransferTest': 4,
       'com.helix.app.proot.ProotProducedFilesTest': 8,
       'com.helix.app.proot.ProotArtifactReferencesTest': 3,
       'com.helix.runtime.proot.core.GuestMediaTest': 8}


def main():
    log = (OUT/'host.log').read_text()
    if 'BUILD SUCCESSFUL' not in log or 'BUILD FAILED' in log:
        raise RuntimeError('No complete successful current host gate')
    modules, fresh = {}, {}
    for name, relative in DIRECTORIES.items():
        files = sorted((ROOT/relative).glob('TEST-*.xml'))
        if not files:
            raise RuntimeError('Missing module reports: ' + relative)
        stats = {key: 0 for key in ('tests', 'failures', 'errors', 'skipped')}
        for file in files:
            element = ET.parse(file).getroot()
            for key in stats:
                stats[key] += int(element.get(key, '0'))
            if element.get('name') in NEW:
                actual = {key: int(element.get(key, '0')) for key in stats}
                expected = NEW[element.get('name')]
                if actual != {'tests': expected, 'failures': 0, 'errors': 0, 'skipped': 0}:
                    raise RuntimeError('New regression did not pass exactly: ' + file.name)
                fresh[element.get('name')] = actual
        if stats['failures'] or stats['errors']:
            raise RuntimeError('Module test failures remain: ' + name)
        modules[name] = stats
    if set(fresh) != set(NEW):
        raise RuntimeError('Missing new regression classes')
    apk = json.loads((OUT/'apk-media-verification.json').read_text())
    baseline = json.loads((OUT/'baseline.json').read_text())
    result = {'head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
              'recorded_at_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'gradle': re.search(r'[\d,]+ actionable tasks: .*', log).group(0),
              'jvm_modules': modules, 'new_jvm_cases': sum(NEW.values()), 'new_jvm_classes': fresh,
              'python_packaging_cases': 4, 'python_command': 'python3 -O scripts/debug/2026-10-02/test-ffmpeg-proot-package.py',
              'android_test_cases_added': 3, 'android_test_status': 'compiled_only_not_executed',
              'device_status': 'not_requested', 'real_account_status': 'not_requested',
              'apk_evidence': apk, 'baseline_provenance': baseline,
              'note': 'The pre-integration APKs were retained existing artifacts, not freshly rebuilt controls. Module totals include skipped tests and cross-flavor duplicates.'}
    (OUT/'host-summary.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({key: result[key] for key in ('head', 'gradle', 'jvm_modules', 'new_jvm_cases', 'python_packaging_cases', 'android_test_cases_added')}, indent=2))


if __name__ == '__main__':
    main()
