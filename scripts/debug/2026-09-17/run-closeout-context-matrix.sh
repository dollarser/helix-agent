#!/usr/bin/env bash
# Post-merge regression for model/UI result boundaries and budget continuation.
# Each quadrant owns a fresh emulator; the shared runner verifies identity and
# shuts down only its process in finally. Outputs remain under ignored build/.
set -euo pipefail
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$project_root"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
output_root="${1:?Pass a new output directory below build/}"
case "$output_root" in build/*) ;; *) echo 'Output must be below build/' >&2; exit 2 ;; esac
classes='com.helix.app.chat.ToolResultReadDeviceTest,com.helix.app.chat.ChatScreenProjectionDeviceTest,com.helix.app.chat.ChatServiceAttachmentRetryDeviceTest,com.helix.app.ui.RunControlSettingsUiDeviceTest,com.helix.app.chat.LongTurnCompactionDeviceTest,com.helix.app.chat.ContextCompactionDeviceTest'
for quadrant in 'consumer 29 5566 Helix_API_29' 'developer 36 5564 Helix_API_36_test' 'consumer 36 5568 Helix_API_36_test' 'developer 29 5570 Helix_API_29'; do
    read -r flavor api port avd <<< "$quadrant"
    package=com.helix.agent
    if [[ "$flavor" == developer ]]; then package+=.developer; fi
    python3 scripts/debug/2026-09-09/run-owned-emulator.py \
        --avd "$avd" --port "$port" \
        --apk "app/build/outputs/apk/$flavor/debug/app-$flavor-debug.apk" \
        --test-apk "app/build/outputs/apk/androidTest/$flavor/debug/app-$flavor-debug-androidTest.apk" \
        --runner "$package.test/com.helix.app.HelixAndroidJUnitRunner" \
        --classes "$classes" --timeout 300 --output "$output_root/$flavor-api$api"
done
