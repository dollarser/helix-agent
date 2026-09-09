#!/bin/sh
set -eu
variant=$1
api=$2
port=$3
output=$4
suffix=
if [ "$variant" = developer ]; then suffix=.developer; fi
classes=com.helix.app.chat.GoalModelReportDeviceTest,com.helix.app.ui.GoalModelReportFlowDeviceTest,com.helix.app.chat.GoalRunCoordinatorDeviceTest,com.helix.app.chat.GoalTurnBindingDeviceTest,com.helix.app.chat.GoalUsageReservationsDeviceTest,com.helix.app.chat.GoalModelCancellationDeviceTest,com.helix.app.chat.BackgroundTaskStorageDeviceTest,com.helix.app.chat.ContextCompactionDeviceTest,com.helix.app.chat.LongTurnCompactionDeviceTest,com.helix.app.ui.ChatCompactionFlowDeviceTest,com.helix.app.ui.BackgroundTaskFlowDeviceTest,com.helix.app.ui.GoalEditorDeviceTest,com.helix.app.ui.GoalDialogDeviceTest
python3 scripts/debug/2026-09-09/run-owned-emulator.py \
 --avd "Helix_M11_Test_API_$api" --port "$port" \
 --apk "app/build/outputs/apk/$variant/debug/app-$variant-debug.apk" \
 --test-apk "app/build/outputs/apk/androidTest/$variant/debug/app-$variant-debug-androidTest.apk" \
 --runner "com.helix.agent$suffix.test/com.helix.app.HelixAndroidJUnitRunner" \
 --classes "$classes" --output "$output"
