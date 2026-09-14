"""Targeted host regressions; never touches shared emulators or real model sessions."""
from pathlib import Path
import subprocess
out = Path('build/debug/2026-09-10/tool-context-fixes')
out.mkdir(parents=True, exist_ok=True)
paths = [
'app/src/main/kotlin/com/helix/app/chat/' + name + '.kt' for name in
['ChatContextUsage','ContextCompaction','SummaryOutputBudget','ToolModelResult','ChatToolMessageEncoder']
] + ['app/src/main/kotlin/com/helix/app/provider/' + name + '.kt' for name in ['ProviderContextSettings','ProviderService']
] + ['app/src/test/kotlin/com/helix/app/chat/' + name + '.kt' for name in ['SummaryOutputBudgetTest','ToolModelResultTest']
] + ['app/src/test/kotlin/com/helix/app/provider/ProviderContextSettingsTest.kt']
commands = [
['./gradlew','spotlessApply','-PspotlessIdeHook=' + ','.join(str(Path(p).resolve()) for p in paths)],
['./gradlew', ':app:testDeveloperDebugUnitTest', '--tests','*SummaryOutputBudgetTest', '--tests','*ToolModelResultTest', '--tests','*TurnBudgetTrackerTest', '--tests','*ProviderContextSettingsTest', '--tests','*ChatContextUsageTest', ':app:assembleDeveloperDebug'],
]
for i, cmd in enumerate(commands):
    with (out / f'{i}.log').open('w') as log:
        result = subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT)
    print(i, result.returncode, flush=True)
    if result.returncode: raise SystemExit(result.returncode)
