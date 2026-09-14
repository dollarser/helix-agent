"""Scoped host verification only. Does not enumerate, start, or access Android devices."""
from pathlib import Path
import subprocess
paths=['runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/CodexSubscriptionSmoke.kt','runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/CodexLoginActivity.kt','runtime/cli-app/src/test/kotlin/com/helix/runtime/cli/app/CodexSmokeRefreshTest.kt','app/src/main/kotlin/com/helix/app/provider/SubscriptionConnectionProvider.kt','app/src/main/kotlin/com/helix/app/provider/ProviderConnectionProbe.kt','app/src/main/kotlin/com/helix/app/provider/ProviderConnectionCheck.kt','app/src/test/kotlin/com/helix/app/provider/ProviderConnectionCheckTest.kt','app/src/developer/kotlin/com/helix/app/provider/CodexSubscriptionProvider.kt','app/src/main/kotlin/com/helix/app/ui/SettingsActions.kt','app/src/main/kotlin/com/helix/app/ui/SettingsScreen.kt','app/src/main/kotlin/com/helix/app/ui/ProotRuntimeSection.kt','app/src/main/kotlin/com/helix/app/ui/AuditScreen.kt','app/src/developer/kotlin/com/helix/app/root/RootModule.kt','app/src/developer/kotlin/com/helix/app/automation/AutomationModule.kt','app/src/main/kotlin/com/helix/app/egress/EgressRuleSection.kt']
p=Path(paths[1]);s=p.read_text().replace('    @Volatile private var activeSmokeJob: CodexModelJobRunner? = null\n','').replace(' || activeSmokeJob != null','').replace('        activeSmokeJob?.close()\n','').replace('        activeSmokeJob = null\n','');p.write_text(s)
out=Path('build/debug/2026-09-10/account-connection-layout');out.mkdir(parents=True,exist_ok=True)
cmds=[['./gradlew','spotlessApply','-PspotlessIdeHook='+','.join(str(Path(p).resolve()) for p in paths)],['./gradlew',':runtime:cli-app:testDebugUnitTest','--tests','*CodexSmoke*',':app:testDeveloperDebugUnitTest','--tests','*ProviderConnectionCheckTest','--tests','*CodexCapabilityProbeTest',':app:assembleDeveloperDebug',':app:assembleConsumerDebug',':runtime:cli-app:assembleDebug']]
cmds.append(["./gradlew", ":app:lintDeveloperDebug", ":app:lintConsumerDebug", ":runtime:cli-app:lintDebug"])
for i,cmd in enumerate(cmds):
 with (out/f'{i}.log').open('w') as log:r=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT)
 print(i,r.returncode,flush=True)
 if r.returncode:raise SystemExit(r.returncode)
