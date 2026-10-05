#!/bin/sh
set -eu
# Requires explicit authorization for this device validation task.
[ "${1:-}" = "--allow-device" ] || { echo "Pass --allow-device after owner authorization" >&2; exit 2; }
: "${ANDROID_HOME:?Set ANDROID_HOME}"
: "${HELIX_TEST_SERIAL:?Set the authorized emulator serial}"
case "$HELIX_TEST_SERIAL" in emulator-[0-9]*) ;; *) echo "Emulator required" >&2; exit 2;; esac
adb="$ANDROID_HOME/platform-tools/adb"
"$adb" -s "$HELIX_TEST_SERIAL" install -r app/build/outputs/apk/developer/debug/app-developer-debug.apk
"$adb" -s "$HELIX_TEST_SERIAL" install -r app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk
"$adb" -s "$HELIX_TEST_SERIAL" shell am instrument -w -r -e class com.helix.app.ui.GlobalDnsDeviceTest,com.helix.app.proot.IntegratedRuntimeUiDeviceTest com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner
