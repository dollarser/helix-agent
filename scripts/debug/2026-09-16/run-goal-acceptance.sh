#!/usr/bin/env bash
# HXA-208: serialize Gradle writers and use only runner-owned emulator processes.
set -euo pipefail
cd "$(dirname "$0")/../../.."
: "${JAVA_HOME:?Set JDK 17 JAVA_HOME}"
: "${ANDROID_HOME:?Set Android SDK ANDROID_HOME}"
./gradlew spotlessApply
./scripts/check-all.sh --all
./gradlew :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest :core:storage:assembleDebugAndroidTest
classes='com.helix.app.chat.GoalContinuationDeviceTest,com.helix.app.ui.GoalLifecycleFlowDeviceTest,com.helix.app.ui.GoalModelReportFlowDeviceTest,com.helix.app.chat.GoalModelCancellationDeviceTest,com.helix.app.chat.GoalRunCoordinatorDeviceTest,com.helix.app.recovery.ProcessRecoveryTest,com.helix.app.GoalDeletionDeviceTest,com.helix.app.ui.GoalDialogDeviceTest,com.helix.app.ui.GoalEditorDeviceTest,com.helix.app.chat.GoalProcessKillDeviceTest,com.helix.app.foreground.DataSyncForegroundServiceDeviceTest#dataSyncForegroundStartsPostsAStoppableNotificationAndStops,com.helix.app.foreground.DataSyncForegroundServiceDeviceTest#rapidStartAndStopDoesNotLeaveAPendingForegroundPromotionOrNotification,com.helix.app.foreground.DataSyncForegroundServiceDeviceTest#controllerStopsTheForegroundServiceWhenTheTurnWaitsForApproval'
python3 scripts/debug/2026-09-16/run-pre-hxa-regressions.py --class "$classes" --goal-kill
python3 scripts/debug/2026-09-16/run-pre-hxa-regressions.py --api36 --class com.helix.app.foreground.DataSyncForegroundServiceDeviceTest#dataSyncForegroundStopsOnTheApi35TimeoutCallback
python3 scripts/debug/2026-09-16/run-goal-storage-migrations.py
