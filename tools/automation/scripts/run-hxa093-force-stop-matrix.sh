#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <adb-serial>" >&2
  exit 2
fi

serial="$1"
package_name="com.helix.tools.automation.test"
runner="$package_name/androidx.test.runner.AndroidJUnitRunner"
apk="tools/automation/build/outputs/apk/androidTest/debug/automation-debug-androidTest.apk"
setup_class="com.helix.tools.automation.AutomationForceStopSetupDeviceTest"
recovery_class="com.helix.tools.automation.AutomationForceStopRecoveryDeviceTest"
original_services="$(adb -s "$serial" shell settings get secure enabled_accessibility_services | tr -d '\r')"
original_accessibility_enabled="$(
  adb -s "$serial" shell settings get secure accessibility_enabled | tr -d '\r'
)"
setup_pid=""

run_test() {
  local test_class="$1"
  local phase="$2"
  local output
  output="$(
    adb -s "$serial" shell am instrument -w -r \
      -e class "$test_class" \
      -e hxa093ForceStopPhase "$phase" \
      "$runner"
  )"
  printf '%s\n' "$output"
  if ! printf '%s\n' "$output" | rg -Fq 'OK (1 test)'; then
    echo "instrumentation failed: $test_class" >&2
    exit 1
  fi
}

./gradlew --no-daemon --max-workers=2 :tools:automation:assembleDebugAndroidTest
adb -s "$serial" install -r -t "$apk" >/dev/null
setup_output="$(mktemp)"

cleanup() {
  if [[ -n "$setup_pid" ]]; then
    kill "$setup_pid" >/dev/null 2>&1 || true
    wait "$setup_pid" >/dev/null 2>&1 || true
  fi
  rm -f "$setup_output"
  if [[ "$original_services" == "null" ]]; then
    adb -s "$serial" shell settings delete secure enabled_accessibility_services >/dev/null 2>&1 || true
  else
    adb -s "$serial" shell settings put secure enabled_accessibility_services "$original_services" >/dev/null 2>&1 || true
  fi
  adb -s "$serial" shell settings put secure accessibility_enabled \
    "$original_accessibility_enabled" >/dev/null 2>&1 || true
  adb -s "$serial" uninstall "$package_name" >/dev/null 2>&1 || true
}
trap cleanup EXIT

adb -s "$serial" shell am instrument -w -r \
  -e class "$setup_class" -e hxa093ForceStopPhase setup \
  "$runner" >"$setup_output" 2>&1 &
setup_pid=$!
ready=false
for ((attempt=0; attempt<45; attempt++)); do
  if rg -Fq 'INSTRUMENTATION_STATUS: hxa093ReadyForHostKill=1' "$setup_output"; then
    ready=true
    break
  fi
  if ! kill -0 "$setup_pid" 2>/dev/null; then break; fi
  sleep 1
done
if [[ "$ready" != true ]]; then
  cat "$setup_output"
  echo "setup did not establish a live session for host force-stop" >&2
  exit 1
fi
live_pid="$(adb -s "$serial" shell pidof "$package_name" | tr -d '\r')"
if [[ -z "$live_pid" ]]; then
  echo "setup process already exited before host force-stop" >&2
  exit 1
fi
if ! adb -s "$serial" shell dumpsys notification --noredact |
  awk '/Notification List:/{active=1} /mArchive=/{active=0} active' |
  rg -F "|$package_name|4900|" >/dev/null; then
  echo "live session notification absent before host force-stop" >&2
  exit 1
fi
echo "host force-stop targets live session pid=$live_pid with active notification"
adb -s "$serial" shell am force-stop "$package_name"
wait "$setup_pid" || true
setup_pid=""
cat "$setup_output"

if adb -s "$serial" shell pidof "$package_name" | rg -q '[0-9]'; then
  echo "force-stop left the automation process alive" >&2
  exit 1
fi
if adb -s "$serial" shell dumpsys notification --noredact |
  awk '/Notification List:/{active=1} /mArchive=/{active=0} active' |
  rg -F "|$package_name|4900|" >/dev/null; then
  echo "force-stop left the automation foreground notification visible" >&2
  exit 1
fi

run_test "$recovery_class" recovery
echo "HXA-093 force-stop matrix passed on $serial"
