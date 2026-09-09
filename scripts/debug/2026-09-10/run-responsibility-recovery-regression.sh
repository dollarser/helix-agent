#!/bin/sh
set -eu
variant=$1
api=$2
port=$3
output=$4
suffix=
if [ "$variant" = developer ]; then suffix=.developer; fi
setup=com.helix.app.ui.FileTransferRecoveryDeviceTest
classes=$setup,com.helix.app.files.ManualSafFileDeviceTest,com.helix.app.ui.FilesScreenTest,com.helix.app.ui.FilesHomeDeviceTest,com.helix.app.ui.FilesImportExportUiTest,com.helix.app.ui.ManualSharedFileDeviceTest,com.helix.app.ToolSchedulerDeviceTest,com.helix.app.ApprovalFlowDeviceTest,com.helix.app.ApprovalWakeLatencyDeviceTest,com.helix.app.chat.ChatServiceAttachmentRetryDeviceTest,com.helix.app.chat.ChatSessionLifecycleDeviceTest,com.helix.app.ui.ChatStopProgressDeviceTest,com.helix.app.ui.ToolTimelineLayoutDeviceTest,com.helix.app.ui.ApprovalLayoutDeviceTest
python3 scripts/debug/2026-09-09/run-owned-emulator.py --avd "Helix_M11_Test_API_$api" --port "$port" \
 --apk "app/build/outputs/apk/$variant/debug/app-$variant-debug.apk" \
 --test-apk "app/build/outputs/apk/androidTest/$variant/debug/app-$variant-debug-androidTest.apk" \
 --runner "com.helix.agent$suffix.test/com.helix.app.HelixAndroidJUnitRunner" \
 --classes "$classes" --recovery-setup-class "$setup" --instrument-arg recoveryPhase=verify \
 --grant-shared-storage --output "$output"
