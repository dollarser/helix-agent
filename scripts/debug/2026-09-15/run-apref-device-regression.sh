#!/usr/bin/env bash
# HXA-200 device acceptance (ADR-PERMISSIONS-003, 2026-09-14 clarification). Owned exclusive emulators,
# always closed in finally.
#
# Runs the two device suites tied to this work, on BOTH flavors (consumer + developer) and BOTH
# APIs (36 + 29):
#   - NEW:  com.helix.app.ToolApprovalPreferenceDeviceTest  (UNSET-L0 card-free, explicit ASK,
#             invalidated ALLOW -> ALLOW_INVALIDATED card, cross-scope DENY > ASK > ALLOW; all under
#             the production dispatcher/preference seam + real Room + real broker)
#   - REG:  com.helix.app.ToolSchedulerDeviceTest           (READ_ONLY L0 card-free must stay green)
#
# Exclusive-device rule: refuses to run if ANY device is already attached (never borrow another
# task's device), boots a fresh owned emulator per API, and kills only that owned process in
# finally. The device Room persists across runs, so the app + test packages are uninstalled after
# each flavor to keep state clean.
#
# A targeted device regression, not the full browser/Autofill soak or the OEM matrix.
set -uo pipefail
# Build environment: prefer the caller's env; fall back to standard macOS/Homebrew locations so
# no user's home directory is hardcoded. JDK 17 and the Android SDK (platform-tools + emulator)
# must resolve or the script exits before touching a device.
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
[ -n "${JAVA_HOME:-}" ] || { echo "JAVA_HOME (JDK 17) must be set in the environment"; exit 1; }
[ -d "$ANDROID_HOME/platform-tools" ] || { echo "ANDROID_HOME platform-tools not found under $ANDROID_HOME"; exit 1; }
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
cd "$(git rev-parse --show-toplevel)"

TS=$(date +%Y%m%d-%H%M%S)
OUT="build/hxa200-device-$TS"
mkdir -p "$OUT"
echo "OUT=$OUT"
echo "host ABI = $(uname -m)" | tee "$OUT/host.txt"

attached() { command adb devices | awk 'NR>1 && $2=="device"{print $1}'; }

APREF="com.helix.app.ToolApprovalPreferenceDeviceTest"
SCHED="com.helix.app.ToolSchedulerDeviceTest"

run_one() {
  # $1 = flavor (consumer|developer), $2 = test class, $3 = log file
  local FLAVOR="$1" CLS="$2" LOG="$3"
  local FLAVORCAP
  case "$FLAVOR" in
    consumer) FLAVORCAP="Consumer" ;;
    developer) FLAVORCAP="Developer" ;;
    *) echo "unknown flavor $FLAVOR" > "$LOG"; return 1 ;;
  esac
  local TASK=":app:connected${FLAVORCAP}DebugAndroidTest"
  ./gradlew "$TASK" -Pandroid.testInstrumentationRunnerArguments.class="$CLS" \
    --console=plain > "$LOG" 2>&1
  local rc=$?
  echo "rc=$rc task=$TASK class=$CLS" >> "$LOG"
  # Surface the JUnit execution evidence (counts, not just BUILD status) for the report.
  grep -aE "Starting [0-9]+ tests|Finished [0-9]+ tests|BUILD (SUCCESSFUL|FAILED)|actionable tasks" "$LOG" \
    | tail -4 | sed 's/^/  /'
  return $rc
}

run_api() {
  local AVD="$1" PORT="$2"; local SERIAL="emulator-$PORT"
  local EPID=""
  if [ -n "$(attached)" ]; then
    echo "REFUSE existing device for $AVD (exclusive rule)" | tee -a "$OUT/summary.txt"
    return 1
  fi
  echo "== boot $AVD on $SERIAL ==" | tee -a "$OUT/summary.txt"
  emulator -avd "$AVD" -port "$PORT" -no-window -no-audio -no-boot-anim -no-snapshot-load \
    -memory 2048 -cores 2 -gpu swiftshader_indirect > "$OUT/emulator-$AVD.log" 2>&1 &
  EPID=$!
  local booted=0
  for _ in $(seq 1 150); do
    if command adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | grep -q 1; then booted=1; break; fi
    sleep 2
  done
  if [ "$booted" != 1 ]; then
    echo "BOOT TIMEOUT $AVD" | tee -a "$OUT/summary.txt"
    kill -9 "$EPID" 2>/dev/null
    return 1
  fi
  local API; API=$(command adb -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')
  command adb -s "$SERIAL" shell wm size 1080x2400 >/dev/null 2>&1
  command adb -s "$SERIAL" shell wm density 420 >/dev/null 2>&1
  echo "booted $AVD api=$API" | tee -a "$OUT/summary.txt"
  sleep 5

  local results=()
  for FLAVOR in consumer developer; do
    run_one "$FLAVOR" "$APREF" "$OUT/apref-$FLAVOR-api$API.log"; results+=("apref-$FLAVOR=$?")
    run_one "$FLAVOR" "$SCHED" "$OUT/sched-$FLAVOR-api$API.log"; results+=("sched-$FLAVOR=$?")
    # Fresh state for the next flavor (established practice: clear the two helix packages).
    for p in $(command adb -s "$SERIAL" shell pm list packages 2>/dev/null | sed 's/^package://' | grep -i helix); do
      command adb -s "$SERIAL" uninstall "$p" >/dev/null 2>&1
    done
  done

  # finally-equivalent: kill only the emulator process group we started.
  kill "$EPID" 2>/dev/null; sleep 4; kill -9 "$EPID" 2>/dev/null
  echo "RESULT $AVD (api=$API): ${results[*]}" | tee -a "$OUT/summary.txt"
}

# Wait for the previous emulator to fully detach before booting the next.
detach_all() {
  for _ in $(seq 1 60); do
    [ -z "$(attached)" ] && return 0
    sleep 2
  done
  echo "WARN: a device is still attached; refusing to start the next API" | tee -a "$OUT/summary.txt"
  return 1
}

run_api Helix_API_36 5560
detach_all && run_api Helix_API_29 5562

command adb devices | tee "$OUT/adb-final.txt"
echo "DONE -> $OUT" | tee -a "$OUT/summary.txt"
