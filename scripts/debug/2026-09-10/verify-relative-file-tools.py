"""Verify session path binding, template packaging and workspace compatibility without devices."""
from pathlib import Path
import subprocess

out = Path('build/debug/2026-09-10/relative-file-tools')
out.mkdir(parents=True, exist_ok=True)
paths = [
 'app/src/main/kotlin/com/helix/app/chat/' + n + '.kt' for n in
 ['FileToolArguments', 'ChatEnvironmentContext', 'ChatRequestAssembler', 'ChatToolCalls', 'ChatService']
]
paths += ['app/src/test/kotlin/com/helix/app/chat/' + n + '.kt' for n in ['FileToolArgumentsTest', 'ChatEnvironmentContextTest']]
paths += ['core/workspace/src/main/kotlin/com/helix/core/workspace/WorkspaceLayout.kt']
paths += ['tools/files/src/main/kotlin/com/helix/tools/files/' + n + '.kt' for n in
 ['WriteTool', 'EditTool', 'FilesMkdirTool', 'FilesCopyTool', 'FilesMoveTool', 'FilesDeleteTool']]
paths += ['tools/files/src/test/kotlin/com/helix/tools/files/WriteToolTest.kt']
paths += ['tools/files/src/test/kotlin/com/helix/tools/files/' + n + '.kt' for n in
 ['EditToolTest', 'FilesMetaToolsTest', 'FilesMutateToolsTest']]
commands = [
 ['./gradlew', 'spotlessApply', '-PspotlessIdeHook=' + ','.join(str(Path(p).resolve()) for p in paths)],
 ['./gradlew', ':tools:files:test', ':core:workspace:test', ':app:testDeveloperDebugUnitTest', '--tests', '*FileToolArgumentsTest', '--tests', '*ChatEnvironmentContextTest', ':app:assembleDeveloperDebug'],
]
for index, command in enumerate(commands):
    with (out / f'{index}.log').open('w') as log:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
    print(f'command {index}: exit={result.returncode}', flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)

import zipfile
import xml.etree.ElementTree as ET
import json
with zipfile.ZipFile('app/build/outputs/apk/developer/debug/app-developer-debug.apk') as apk:
    for name in ['base', 'files', 'plan']:
        assert apk.read(f'prompts/{name}.md').strip(), name
totals = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
for directory in ['tools/files/build/test-results/test', 'core/workspace/build/test-results/test',
                  'app/build/test-results/testDeveloperDebugUnitTest']:
    for path in Path(directory).glob('TEST-*.xml'):
        root = ET.parse(path).getroot()
        for key in totals:
            totals[key] += int(root.attrib.get(key, '0'))
(out / 'summary.json').write_text(json.dumps(totals, indent=2))
print(totals)
