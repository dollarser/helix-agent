#!/usr/bin/env bash
set -euo pipefail

# Only the explicitly selected device is touched; worktrees may share other emulators.
if [[ $# -ne 1 || -z "$1" ]]; then
  echo "Usage: $0 <adb-device-serial>" >&2
  exit 2
fi
connector_serial="$1"
connector_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
connector_adb="${ANDROID_HOME:?Set ANDROID_HOME}/platform-tools/adb"
connector_output="$connector_root/app/build/outputs/connector-acceptance"
mkdir -p "$connector_output"
connector_log="$connector_output/device-${connector_serial//[^a-zA-Z0-9_-]/_}.log"
"$connector_adb" -s "$connector_serial" get-state
"$connector_adb" -s "$connector_serial" shell getprop ro.build.version.sdk
"$connector_adb" -s "$connector_serial" install -r "$connector_root/app/build/outputs/apk/consumer/debug/app-consumer-debug.apk"
"$connector_adb" -s "$connector_serial" install -r "$connector_root/app/build/outputs/apk/androidTest/consumer/debug/app-consumer-debug-androidTest.apk"
"$connector_adb" -s "$connector_serial" shell am instrument -w -r \
  -e class com.helix.app.connector.ConnectorDeviceTest,com.helix.app.connector.ConnectorUiDeviceTest \
  com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner | tee "$connector_log"
# Android's instrument command may exit zero for test failures; inspect its terminal evidence.
rg '^OK \([0-9]+ tests?\)' "$connector_log"
if rg 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$connector_log"; then
  exit 1
fi

for connector_phase in seedPersistedConnector recoverInNewProcess; do
  "$connector_adb" -s "$connector_serial" shell am force-stop com.helix.agent
  connector_phase_log="$connector_output/device-${connector_serial//[^a-zA-Z0-9_-]/_}-$connector_phase.log"
  "$connector_adb" -s "$connector_serial" shell am instrument -w -r \
    -e class "com.helix.app.connector.ConnectorProcessRecoveryDeviceTest#$connector_phase" \
    com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner | tee "$connector_phase_log"
  rg '^OK \(1 test\)' "$connector_phase_log"
done
