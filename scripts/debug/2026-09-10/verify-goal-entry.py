"""Host-only Goal verification; never enumerates or operates Android devices."""
from pathlib import Path
import subprocess,os
root=Path(__file__).resolve().parents[3]
os.chdir(root)
if 'JAVA_HOME' not in os.environ:
 located=subprocess.run(['/usr/libexec/java_home','-v','17'],text=True,capture_output=True)
 os.environ['JAVA_HOME']=located.stdout.strip() if located.returncode==0 else str(Path(subprocess.check_output(['brew','--prefix','openjdk@17'],text=True).strip())/'libexec/openjdk.jdk/Contents/Home')
paths=[p for p in (root/'app/src').rglob('*.kt') if p.name in ['GoalBudgetDefaults.kt','RunControlStore.kt','GoalComposerStart.kt','GoalSettingsSection.kt','GoalDialog.kt','ChatScreen.kt','ChatService.kt','ConversationComposer.kt','SettingsScreen.kt','MainActivity.kt','GoalDefaultsTest.kt','GoalEditorDeviceTest.kt','GoalComposerStartDeviceTest.kt','GoalDialogDeviceTest.kt','GoalReminderControlsDeviceTest.kt','GoalRealModelUiDeviceTest.kt','ChatStopProgressDeviceTest.kt']]
out=root/'build/debug/2026-09-10/goal-entry';out.mkdir(parents=True,exist_ok=True)
cmds=[['./gradlew','spotlessApply','-PspotlessIdeHook='+','.join(map(str,paths))],['./gradlew',':app:testDeveloperDebugUnitTest','--tests','*Goal*','--tests','*RunControlStoreTest',':app:assembleDeveloperDebug',':app:assembleConsumerDebug',':app:compileDeveloperDebugAndroidTestKotlin'],['./gradlew',':app:lintDeveloperDebug',':app:lintConsumerDebug']]
for i,cmd in enumerate(cmds):
 with (out/f'{i}.log').open('w') as log: result=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT)
 print(i,result.returncode,flush=True)
 if result.returncode: raise SystemExit(result.returncode)
