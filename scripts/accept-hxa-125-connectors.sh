#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 1 || -z "$1" ]]; then
  echo "Usage: $0 <dedicated-adb-device-serial>" >&2
  exit 2
fi
connector_serial="$1"
connector_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
connector_adb="${ANDROID_HOME:?Set ANDROID_HOME}/platform-tools/adb"
connector_output="$connector_root/app/build/outputs/hxa-125-device"
mkdir -p "$connector_output"
"$connector_adb" -s "$connector_serial" get-state
"$connector_adb" -s "$connector_serial" shell getprop ro.build.version.sdk
"$connector_adb" -s "$connector_serial" install -r "$connector_root/app/build/outputs/apk/consumer/debug/app-consumer-debug.apk"
"$connector_adb" -s "$connector_serial" install -r "$connector_root/app/build/outputs/apk/androidTest/consumer/debug/app-consumer-debug-androidTest.apk"
for connector_phase in seedRealConnectionAndDispatch recoverRealConnectionInNewProcess; do
  "$connector_adb" -s "$connector_serial" shell am force-stop com.helix.agent
  connector_log="$connector_output/${connector_serial//[^a-zA-Z0-9_-]/_}-$connector_phase.log"
  "$connector_adb" -s "$connector_serial" shell am instrument -w -r \
    -e connectorExternal true -e class "com.helix.app.connector.ConnectorExternalDeviceTest#$connector_phase" \
    com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner | tee "$connector_log"
  rg '^OK \(1 test\)' "$connector_log"
  if rg 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -[234]' "$connector_log"; then
    exit 1
  fi
done
"$connector_adb" -s "$connector_serial" logcat -d -s System.out:I | \
  rg "HXA-125 Android protocol=" > "$connector_output/${connector_serial//[^a-zA-Z0-9_-]/_}-protocol-pids.log"
