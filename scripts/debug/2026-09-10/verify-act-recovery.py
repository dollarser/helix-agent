"""Targeted host regressions and APK builds only; no emulator or model requests."""
from pathlib import Path
import os
import subprocess

out = Path('build/debug/2026-09-10/act-recovery')
out.mkdir(parents=True, exist_ok=True)
paths = [
    'app/src/main/kotlin/com/helix/app/chat/ChatHistoryBuilder.kt',
    'app/src/main/kotlin/com/helix/app/chat/ChatToolMessageEncoder.kt',
    'app/src/main/kotlin/com/helix/app/ui/ConversationMessage.kt',
    'app/src/main/kotlin/com/helix/app/ui/ConversationSection.kt',
    'app/src/test/kotlin/com/helix/app/chat/ChatHistoryBuilderTest.kt',
    'provider/openai-responses/src/main/kotlin/com/helix/provider/openai/responses/ResponsesStreamDecoder.kt',
    'provider/openai-responses/src/test/kotlin/com/helix/provider/openai/responses/ResponsesStreamDecoderTest.kt',
]
commands = [
    ['./gradlew', 'spotlessApply', '-PspotlessIdeHook=' + ','.join(str(Path(p).resolve()) for p in paths)],
    ['./gradlew', ':provider:openai-responses:test', '--tests', '*ResponsesStreamDecoderTest', ':app:testDeveloperDebugUnitTest', '--tests', '*ChatHistoryBuilderTest', ':app:assembleDeveloperDebug', ':runtime:cli-app:assembleDebug'],
]
for index, command in enumerate(commands):
    with (out / f'{index}.log').open('w') as log:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, env=os.environ.copy())
    print(f'command {index}: exit={result.returncode}', flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)
