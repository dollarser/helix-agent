"""Verify file guidance and budgets, then build only the main developer APK."""
from pathlib import Path
import subprocess

out = Path('build/debug/2026-09-10/agent-file-ux')
out.mkdir(parents=True, exist_ok=True)
paths = [
    'app/src/androidTest/kotlin/com/helix/app/ui/ApprovalLayoutDeviceTest.kt',
    'app/src/main/kotlin/com/helix/app/chat/ChatEnvironmentContext.kt',
    'app/src/main/kotlin/com/helix/app/chat/ChatRequestAssembler.kt',
    'app/src/main/kotlin/com/helix/app/ui/ApprovalCardScreen.kt',
    'app/src/main/kotlin/com/helix/app/ui/ConversationMessage.kt',
    'app/src/main/kotlin/com/helix/app/ui/ConversationSection.kt',
    'app/src/main/kotlin/com/helix/app/runcontrol/RunControlStore.kt',
    'app/src/test/kotlin/com/helix/app/runcontrol/RunControlStoreTest.kt',
    'app/src/test/kotlin/com/helix/app/chat/ChatEnvironmentContextTest.kt',
    'tools/files/src/main/kotlin/com/helix/tools/files/WriteTool.kt',
    'tools/files/src/main/kotlin/com/helix/tools/files/FilesListTool.kt',
]
commands = [
    ['./gradlew', 'spotlessApply', '-PspotlessIdeHook=' + ','.join(str(Path(p).resolve()) for p in paths)],
    ['./gradlew', ':app:testDeveloperDebugUnitTest', '--tests', '*RunControlStoreTest', '--tests', '*ChatEnvironmentContextTest',
     ':tools:files:test', '--tests', '*WriteToolTest', '--tests', '*FilesMetaToolsTest', ':app:assembleDeveloperDebug'],
]
for index, command in enumerate(commands):
    with (out / f'{index}.log').open('w') as log:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
    print(f'command {index}: exit={result.returncode}', flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)
