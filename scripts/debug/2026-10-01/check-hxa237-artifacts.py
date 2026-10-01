#!/usr/bin/env python3
"""Verify this task's reports, new XML resources and font packaging; no device or network access."""
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile


def digest(data):
    return hashlib.sha256(data).hexdigest()


def xml_counts(path):
    root = ET.parse(path).getroot()
    return {key: int(root.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')}


resources = {}
for root, filename in [
    ('app/src/main/res', 'usability.xml'),
    ('app/src/developer/res', 'terminal_usability.xml'),
    ('runtime/cli-app/src/main/res', 'codex_client_version.xml'),
]:
    variants = []
    for qualifier in ('values', 'values-en', 'values-zh-rCN'):
        path = Path(root, qualifier, filename)
        entries = ET.parse(path).getroot().findall('string')
        names = [entry.attrib['name'] for entry in entries]
        assert len(names) == len(set(names)), path
        values = {entry.attrib['name']: re.findall(r'%\d+\$[a-zA-Z]', ''.join(entry.itertext())) for entry in entries}
        variants.append(values)
    assert variants[0] == variants[1] == variants[2], filename
    resources[filename] = len(variants[0])

font_sha = 'ab56ea18c5c24d2b909261f0c63a14f9576dfabaf2e9ebd353062aa4149cefc7'
# Only upstream trailing whitespace is normalized; NOTICE retains the original license digest.
license_sha = '11527366bb615a5246481b961bcc1f4e51cf5d44294a0bea01f59b61f400e299'
assert digest(Path('app/src/developer/res/font/inconsolata_regular.ttf').read_bytes()) == font_sha
apks = {}
for variant in ('consumer', 'developer'):
    path = Path(f'app/build/outputs/apk/{variant}/debug/app-{variant}-debug.apk')
    with ZipFile(path) as archive:
        names = archive.namelist()
        font = [name for name in names if name.endswith('/inconsolata_regular.ttf')]
        license_path = 'assets/terminal-licenses/Inconsolata-OFL.txt'
        notice_path = 'assets/terminal-licenses/Inconsolata-NOTICE.txt'
        if variant == 'developer':
            assert len(font) == 1, font
            assert digest(archive.read(font[0])) == font_sha
            assert digest(archive.read(license_path)) == license_sha
            assert font_sha in archive.read(notice_path).decode('utf-8')
        else:
            assert not font and license_path not in names and notice_path not in names
    apks[variant] = {'path': str(path), 'bytes': path.stat().st_size, 'sha256': digest(path.read_bytes())}

reports = {}
for directory, classes in {
    'runtime/cli-app/build/test-results/testDebugUnitTest': ['CodexClientVersionSettingsTest', 'CodexSmokeRefreshTest'],
    'app/build/test-results/testConsumerDebugUnitTest': ['ProviderSetupStepTest'],
    'app/build/test-results/testDeveloperDebugUnitTest': ['ProviderSetupStepTest', 'TerminalShortcutsTest'],
}.items():
    for name in classes:
        matches = list(Path(directory).glob(f'TEST-*.{name}.xml'))
        assert len(matches) == 1, (directory, name)
        result = xml_counts(matches[0])
        assert result['tests'] > 0 and not any(result[key] for key in ('failures', 'errors', 'skipped')), result
        reports[str(matches[0])] = result

summary = {'resources': resources, 'fontSha256': font_sha, 'apks': apks, 'reports': reports}
Path('build/hxa237/artifact-summary.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
print(json.dumps(summary, indent=2))
