#!/bin/sh
set -eu
output_prefix=$1
for attempt in 1 2 3; do
 python3 scripts/debug/2026-09-09/run-owned-emulator.py --avd Helix_M11_Test_API_36 --port "$((5582 + attempt * 2))" \
  --apk app/build/outputs/apk/developer/debug/app-developer-debug.apk \
  --test-apk app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk \
  --runner com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner \
  --classes 'com.helix.app.ui.FilesScreenTest#previewsTextFileWithHashInfo' \
  --grant-shared-storage --output "$output_prefix-$attempt"
done
