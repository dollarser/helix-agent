"""Scoped host tests and builds for editable DNS presets and Turn budgeting."""
from pathlib import Path
import subprocess
out=Path('build/debug/2026-09-10/dns-turn-defaults');out.mkdir(parents=True,exist_ok=True)
paths=['app/src/main/kotlin/com/helix/app/runcontrol/RunControlStore.kt','app/src/main/kotlin/com/helix/app/chat/ChatRequestAssembler.kt','app/src/test/kotlin/com/helix/app/runcontrol/RunControlStoreTest.kt','runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/SubscriptionNetworkSettingsActivity.kt']
commands=[['./gradlew','spotlessApply','-PspotlessIdeHook='+','.join(str(Path(p).resolve()) for p in paths)],['./gradlew',':app:testDeveloperDebugUnitTest','--tests','*RunControlStoreTest','--tests','*TurnBudgetTrackerTest','--tests','*ContextRequestTest',':runtime:cli-app:testDebugUnitTest','--tests','*SubscriptionDnsSettingsTest',':app:assembleDeveloperDebug',':runtime:cli-app:assembleDebug']]
for i,cmd in enumerate(commands):
    with (out/f'{i}.log').open('w') as log:r=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT)
    print(i,r.returncode,flush=True)
    if r.returncode:raise SystemExit(r.returncode)
