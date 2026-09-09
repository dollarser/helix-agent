#!/bin/sh
set -eu
variant=$1
api=$2
port=$3
output=$4
suffix=
if [ "$variant" = developer ]; then suffix=.developer; fi
classes=com.helix.app.files.ManualSafFileDeviceTest,com.helix.app.ui.FilesScreenTest,com.helix.app.ui.FilesHomeDeviceTest,com.helix.app.ui.FilesImportExportUiTest,com.helix.app.ui.ManualSharedFileDeviceTest
python3 scripts/debug/2026-09-09/run-owned-emulator.py --avd "Helix_M11_Test_API_$api" --port "$port" \
 --apk "app/build/outputs/apk/$variant/debug/app-$variant-debug.apk" \
 --test-apk "app/build/outputs/apk/androidTest/$variant/debug/app-$variant-debug-androidTest.apk" \
 --runner "com.helix.agent$suffix.test/com.helix.app.HelixAndroidJUnitRunner" \
 --classes "$classes" --grant-shared-storage --output "$output"
