#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 2 || ! -f "$2/github.zip" || ! -f "$2/kling-ai-plugin.zip" ]]; then
  echo "Usage: $0 <dedicated-serial> <prepared-sample-directory>" >&2
  exit 2
fi
sample_serial="$1"
sample_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sample_adb="${ANDROID_HOME:?Set ANDROID_HOME}/platform-tools/adb"
sample_out="$sample_root/build/main-verification/workbuddy-sample"
mkdir -p "$sample_out"
"$sample_adb" -s "$sample_serial" install -r "$sample_root/app/build/outputs/apk/consumer/debug/app-consumer-debug.apk"
"$sample_adb" -s "$sample_serial" install -r "$sample_root/app/build/outputs/apk/androidTest/consumer/debug/app-consumer-debug-androidTest.apk"
"$sample_adb" -s "$sample_serial" shell mkdir -p /sdcard/Android/data/com.helix.agent/files
for sample_name in github kling-ai-plugin; do
  "$sample_adb" -s "$sample_serial" push "$2/$sample_name.zip" /sdcard/Android/data/com.helix.agent/files/
done
sample_log="$sample_out/${sample_serial//[^a-zA-Z0-9_-]/_}.log"
"$sample_adb" -s "$sample_serial" shell am instrument -w -r \
  -e workBuddySample true -e class com.helix.app.connector.WorkBuddySuppliedArchiveDeviceTest \
  com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner | tee "$sample_log"
rg '^OK \(1 test\)' "$sample_log"
if rg 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -[234]' "$sample_log"; then
  exit 1
fi
rg '^INSTRUMENTATION_STATUS_CODE: 0' "$sample_log"
