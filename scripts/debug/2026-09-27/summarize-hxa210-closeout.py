"""Audit exact APKs, instrumentation, owned teardown and screenshots for Workspace closeout."""
import hashlib
import json
from pathlib import Path
import re
import struct
import tarfile

root = Path(__file__).resolve().parents[3]
base = root / 'build/hxa210'
runs = {
    'consumer-workspace-final-api29-r3': 22,
    'consumer-workspace-final-api36-r3': 22,
    'developer-workspace-final-api29-r3': 26,
    'developer-workspace-final-api36-r4': 26,
}
records = []
for name, count in runs.items():
    directory = base / 'device' / name
    assert json.loads((directory / 'closed.json').read_text())['exit'] == 0, name
    result = (directory / 'instrumentation.txt').read_text()
    assert f'OK ({count} tests)' in result and 'FAILURES!!!' not in result, name
    widths = json.loads((directory / 'cleanup-widths.json').read_text())
    cuts = json.loads((directory / 'cleanup-recovery-cuts.json').read_text())
    assert len(widths) == 3 and all(item['tests'] == 6 and item['result'] == 'passed' for item in widths)
    assert len(cuts) == 6 and all(item['verify'] == 'passed' for item in cuts)
    screenshots = []
    with tarfile.open(directory / 'cleanup-screenshots.tar') as archive:
        for member in archive.getmembers():
            if not member.isfile():
                continue
            match = re.fullmatch(r'hxa210-ui-evidence/cleanup-(320|360|412)-(EN|ZH_CN|SYSTEM)-(1|2)\.0\.png', member.name)
            assert match, member.name
            data = archive.extractfile(member).read()
            assert data[:8] == b'\x89PNG\r\n\x1a\n'
            dimensions = struct.unpack('>II', data[16:24])
            assert dimensions == (int(match[1]) * 5 // 2, 2400), (member.name, dimensions)
            screenshots.append({'path': member.name, 'sha256': hashlib.sha256(data).hexdigest()})
    assert len(screenshots) == 18, name
    identities = json.loads((directory / 'artifacts.json').read_text())
    for kind, digest in identities.items():
        assert hashlib.sha256((directory / f'{kind}.apk').read_bytes()).hexdigest() == digest
    records.append({'run': name, 'mainTests': count, 'widthTests': 18, 'recoveryTests': 6,
                    'result': 'passed', 'apkSha256': identities, 'screenshots': screenshots,
                    'device': json.loads((directory / 'device-properties.json').read_text())})
for flavor in ('consumer', 'developer'):
    assert len({r['apkSha256']['app'] for r in records if r['run'].startswith(flavor)}) == 1
# Developer API36 adds timeout diagnostics only; preserve each test APK identity independently.
summary = {'scope': 'HXA-210 bounded local emulator acceptance', 'tests': sum(r['mainTests'] + 24 for r in records),
           'runs': records, 'physicalDevice': 'not requested', 'realProvider': 'not requested',
           'fixtureDifference': 'developer API36 r4 adds second-request timeout diagnostics; assertions unchanged'}
(base / 'workspace-closeout-device-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps({'tests': summary['tests'], 'runs': [r['run'] for r in records]}, indent=2))
