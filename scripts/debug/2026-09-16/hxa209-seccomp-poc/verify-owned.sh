#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../../../../"
sdk=${ANDROID_HOME:-$HOME/Library/Android/sdk}
adb="$sdk/platform-tools/adb"
serial=emulator-5580
out=build/hxa209-poc
if "$adb" devices | awk '{print $1}' | grep -qx "$serial"; then echo 'Port already owned'; exit 1; fi
"$sdk/emulator/emulator" -avd Helix_API_36 -port 5580 -read-only -no-snapshot -no-window -no-audio > "$out/owned-emulator.log" 2>&1 &
owned_pid=$!
cleanup() { kill "$owned_pid" 2>/dev/null || true; wait "$owned_pid" 2>/dev/null || true; }
trap cleanup EXIT
ready=0
for ((i=0;i<180;i++)); do
  kill -0 "$owned_pid" || exit 1
  if [ "$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then ready=1; break; fi
  sleep 1
done
[ "$ready" = 1 ]
p=/data/local/tmp/helix-guard-review
"$adb" -s "$serial" shell mkdir -p "$p"
for tool in guard filterprobe diag netprobe; do
  "$adb" -s "$serial" push "$out/$tool" "$p/$tool"
done
"$adb" -s "$serial" shell "chmod 700 $p/*; sha256sum $p/*"
"$adb" -s "$serial" shell "$p/diag"
"$adb" -s "$serial" shell "$p/filterprobe"
"$adb" -s "$serial" shell "echo synthetic > $p/input; $p/guard /system/bin/sh -c 'echo exec-inheritance-ok; $p/netprobe' < $p/input > $p/output 2>&1"
"$adb" -s "$serial" shell "cat $p/output"
"$adb" -s "$serial" shell "rm -rf $p"
