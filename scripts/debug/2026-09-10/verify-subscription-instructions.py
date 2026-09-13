"""Targeted subscription encoder/retry checks and the isolated diagnostic APK build."""
from pathlib import Path
import subprocess
out = Path('build/debug/2026-09-10/subscription-instructions')
out.mkdir(parents=True, exist_ok=True)
paths = [
 'app/src/main/kotlin/com/helix/app/chat/ChatService.kt',
 'app/src/main/kotlin/com/helix/app/chat/RetryMessageSource.kt',
 'app/src/test/kotlin/com/helix/app/chat/RetryMessageSourceTest.kt',
 'runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/CodexSubscriptionModel.kt',
 'runtime/cli-app/src/test/kotlin/com/helix/runtime/cli/app/CodexToolNamesTest.kt',
 'runtime/cli-app/src/androidTest/kotlin/com/helix/runtime/cli/app/CodexRealAccountDiagnosticTest.kt',
]
commands = [
 ['./gradlew', 'spotlessApply', '-PspotlessIdeHook=' + ','.join(str(Path(p).resolve()) for p in paths)],
 ['./gradlew', ':runtime:cli-app:testDebugUnitTest', '--tests', '*CodexToolNamesTest', ':app:testDeveloperDebugUnitTest',
  '--tests', '*RetryMessageSourceTest', ':runtime:cli-app:assembleDebug', ':runtime:cli-app:assembleDebugAndroidTest', ':app:assembleDeveloperDebug'],
]
for i, command in enumerate(commands):
    with (out / f'{i}.log').open('w') as log:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
    print(i, result.returncode, flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)
