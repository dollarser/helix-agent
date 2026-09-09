#!/bin/sh
set -eu
variant=$1
api=$2
port=$3
output=$4
suffix=
if [ "$variant" = developer ]; then suffix=.developer; fi
classes=com.helix.app.files.ImportExportFacadeDeviceTest,com.helix.app.ToolSchedulerDeviceTest,com.helix.app.ApprovalFlowDeviceTest,com.helix.app.ApprovalWakeLatencyDeviceTest,com.helix.app.chat.ChatServiceAttachmentRetryDeviceTest,com.helix.app.chat.ChatSessionLifecycleDeviceTest,com.helix.app.ui.ChatStopProgressDeviceTest,com.helix.app.ui.ToolTimelineLayoutDeviceTest,com.helix.app.ui.ApprovalLayoutDeviceTest,com.helix.app.ui.SessionDraftDeviceTest,com.helix.app.ui.SessionModelDeviceTest,com.helix.app.ui.ConversationHeaderDeviceTest,com.helix.app.ui.ConversationTopBarDeviceTest,com.helix.app.ui.ProviderFlowTest,com.helix.app.ui.ProviderContextDeviceTest,com.helix.app.ui.FilesImportExportUiTest,com.helix.app.ui.FilesScreenTest,com.helix.app.files.ManualSafFileDeviceTest,com.helix.app.ui.ManualSharedFileDeviceTest,com.helix.app.chat.GoalModelReportDeviceTest,com.helix.app.ui.GoalModelReportFlowDeviceTest,com.helix.app.chat.GoalRunCoordinatorDeviceTest,com.helix.app.chat.GoalTurnBindingDeviceTest,com.helix.app.chat.GoalUsageReservationsDeviceTest,com.helix.app.chat.GoalModelCancellationDeviceTest,com.helix.app.chat.BackgroundTaskStorageDeviceTest,com.helix.app.chat.ContextCompactionDeviceTest,com.helix.app.chat.LongTurnCompactionDeviceTest,com.helix.app.ui.ChatCompactionFlowDeviceTest,com.helix.app.ui.BackgroundTaskFlowDeviceTest,com.helix.app.ui.GoalEditorDeviceTest,com.helix.app.ui.GoalDialogDeviceTest,com.helix.app.McpToolDiscoveryDeviceTest,com.helix.app.a2a.A2aTaskRunnerDeviceTest,com.helix.app.a2a.A2aDiscoveryDeviceTest,com.helix.app.language.AppLanguageDeviceTest,com.helix.app.provider.ProviderModelDiscoveryUiTest,com.helix.app.ui.LanScopeSettingsDeviceTest,com.helix.app.ui.FilesHomeDeviceTest
python3 scripts/debug/2026-09-09/run-owned-emulator.py --avd "Helix_M11_Test_API_$api" --port "$port" \
 --apk "app/build/outputs/apk/$variant/debug/app-$variant-debug.apk" \
 --test-apk "app/build/outputs/apk/androidTest/$variant/debug/app-$variant-debug-androidTest.apk" \
 --runner "com.helix.agent$suffix.test/com.helix.app.HelixAndroidJUnitRunner" \
 --classes "$classes" --grant-shared-storage --output "$output" --timeout 2400 \
 --after-script scripts/debug/2026-09-10/run-hxa184-modules.py
