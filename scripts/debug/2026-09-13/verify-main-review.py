"""Bounded main-only host checks; never operates a device or another worktree."""
from pathlib import Path
import os
import subprocess

root = Path(__file__).resolve().parents[3]
assert subprocess.check_output(["git", "branch", "--show-current"], cwd=root, text=True).strip() == "main"
out = root / "build/debug/2026-09-13/main-review"
out.mkdir(parents=True, exist_ok=True)
env = os.environ.copy()
if not env.get("JAVA_HOME"):
    prefix = subprocess.check_output(["brew", "--prefix", "openjdk@17"], text=True).strip()
    env["JAVA_HOME"] = str(Path(prefix) / "libexec/openjdk.jdk/Contents/Home")
paths = [
    "app/src/main/kotlin/com/helix/app/chat/ChatService.kt",
    "app/src/main/kotlin/com/helix/app/chat/ChatToolCalls.kt",
    "app/src/main/kotlin/com/helix/app/chat/PendingTurnApprovals.kt",
    "app/src/main/kotlin/com/helix/app/chat/TurnCoordinator.kt",
    "app/src/main/kotlin/com/helix/app/DefaultAppContainer.kt",
    "app/src/main/kotlin/com/helix/app/foreground/DataSyncForegroundService.kt",
    "app/src/test/kotlin/com/helix/app/chat/PendingTurnApprovalsTest.kt",
    "app/src/androidTest/kotlin/com/helix/app/chat/ChatServiceAttachmentRetryDeviceTest.kt",
    "app/src/androidTest/kotlin/com/helix/app/chat/TurnCoordinatorDeviceTest.kt",
    "core/agent/src/main/kotlin/com/helix/core/agent/GoalReducer.kt",
    "core/storage/src/main/kotlin/com/helix/core/storage/dao/A2aDaos.kt",
    "core/storage/src/main/kotlin/com/helix/core/storage/repository/A2aTaskRepository.kt",
    "core/storage/src/test/kotlin/com/helix/core/storage/repository/A2aTaskRepositoryTest.kt",
    "provider/api/src/main/kotlin/com/helix/provider/api/CapabilityProbe.kt",
    "provider/api/src/test/kotlin/com/helix/provider/api/CapabilityProbeTest.kt",
    "runtime/cli-client/src/main/kotlin/com/helix/runtime/cli/client/CliRuntimeSupervisor.kt",
    "runtime/cli-client/src/androidTest/kotlin/com/helix/runtime/cli/client/CliRuntimeBindingDeviceTest.kt",
    "runtime/proot-client/src/main/kotlin/com/helix/runtime/proot/client/ProotRuntimeSupervisor.kt",
    "runtime/proot-app/src/main/kotlin/com/helix/runtime/proot/app/ProotJobRunner.kt",
    "runtime/proot-app/src/main/kotlin/com/helix/runtime/proot/app/ProotOutputCapture.kt",
    "runtime/proot-app/src/test/kotlin/com/helix/runtime/proot/app/ProotOutputCaptureTest.kt",
    "tools/browser/src/main/kotlin/com/helix/tools/browser/SensitiveFieldClassifier.kt",
    "tools/browser/src/test/kotlin/com/helix/tools/browser/SensitiveFieldClassifierTest.kt",
    "feature/browser/src/main/kotlin/com/helix/feature/browser/snapshot/BrowserActionScript.kt",
    "feature/browser/src/main/kotlin/com/helix/feature/browser/snapshot/BrowserSnapshotScript.kt",
]
commands = [
    ["./gradlew", "spotlessApply", "-PspotlessIdeHook=" + ",".join(str(root / p) for p in paths), "--max-workers=1"],
    ["./gradlew", ":app:testDeveloperDebugUnitTest", "--tests", "*PendingTurnApprovalsTest",
     "--tests", "*SessionTurnAdmissionTest", "--tests", "*ChatHistoryBuilderTest", "--tests", "*TurnCoordinatorTest",
     ":core:storage:testDebugUnitTest", ":provider:api:test", ":tools:browser:test",
     ":app:compileDeveloperDebugAndroidTestKotlin", ":app:compileConsumerDebugKotlin",
     ":runtime:cli-client:compileDebugAndroidTestKotlin", ":runtime:proot-client:compileDebugKotlin",
     ":runtime:proot-app:testDebugUnitTest", ":feature:browser:testDebugUnitTest",
     "--max-workers=1"],
    ["./gradlew", ":app:lintDeveloperDebug", ":app:lintConsumerDebug", ":runtime:cli-client:lintDebug",
     ":runtime:proot-client:lintDebug", ":runtime:proot-app:lintDebug", "--max-workers=1"],
]
for index, command in enumerate(commands):
    with (out / f"{index}.log").open("w") as log:
        result = subprocess.run(command, cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT)
    print(index, result.returncode, flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)
