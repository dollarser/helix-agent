#!/usr/bin/env bash
# HXA-083 orchestrated lifecycle acceptance (environment.md convention: method-level
# `am instrument -e class X#method`).
#
# The plain gradle connected run covers every in-app scenario. Three lifecycle
# phases need host-side state an app process cannot create (force-stop, uninstall,
# observing whether the app startup started the companion process); this script
# sets that state and drives each phase on the target device.
#
# Usage:  scripts/accept-hxa-083-lifecycle.sh [serial]     (default: single attached device)
# Needs:  JAVA_HOME (or java on PATH), ANDROID_HOME (or adb on PATH).
set -euo pipefail

cd "$(dirname "$0")/.."
REPO_ROOT=$(pwd)
command -v java >/dev/null 2>&1 || { echo "java not found — set JAVA_HOME"; exit 1; }

# Resolve adb: PATH first, then the standard SDK locations.
if command -v adb >/dev/null 2>&1; then
  :
elif [[ -n "${ANDROID_HOME:-}" && -x "$ANDROID_HOME/platform-tools/adb" ]]; then
  PATH="$ANDROID_HOME/platform-tools:$PATH"
elif [[ -n "${ANDROID_SDK_ROOT:-}" && -x "$ANDROID_SDK_ROOT/platform-tools/adb" ]]; then
  PATH="$ANDROID_SDK_ROOT/platform-tools:$PATH"
elif [[ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]]; then
  PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
fi
command -v adb >/dev/null 2>&1 || { echo "adb not found — set ANDROID_HOME or put platform-tools on PATH"; exit 1; }

if [[ -n "${1:-}" ]]; then
  SERIAL="$1"
elif [[ -n "${ANDROID_SERIAL:-}" ]]; then
  SERIAL="$ANDROID_SERIAL"
else
  SERIAL=$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1; exit}')
  [[ -n "$SERIAL" ]] || { echo "no attached device; pass a serial argument"; exit 1; }
fi
ADB="adb -s $SERIAL"
echo "device: $SERIAL"

RUNNER="com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner"
CLASS="com.helix.app.proot.ProotRuntimeBindingE2eDeviceTest"
COMPANION="com.helix.runtime.proot"
MAIN_APP="com.helix.agent.developer"

# ---------------------------------------------------------------- build + install
./gradlew :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest :runtime:proot-app:assembleDebug
$ADB install -r app/build/outputs/apk/developer/debug/app-developer-debug.apk
$ADB install -r app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk
$ADB install -r runtime/proot-app/build/outputs/apk/debug/proot-app-debug.apk

# ps -A: PID is column 2, NAME is last. (pidof matches the 15-char comm and
# cannot see a full package name on Android.)
companion_pidof() { $ADB shell ps -A | { grep -w "$COMPANION" || true; } | awk '{print $2}' | tr -d \'\r\'; }

# Raw `am instrument -w -r` summaries (no gradle reformatting): a green single
# method prints `OK (1 test)`; a red one prints `FAILURES!!!` plus
# `Tests run: 1,  Failures: 1`; an assumption skip reports `Skipped: 1`.
run_phase() {
  local method="$1"
  echo "==> phase: $method"
  warm_main_app
  local out
  out=$($ADB shell am instrument -w -r -e class "$CLASS#$method" "$RUNNER" 2>&1) || true
  echo "$out" | { grep -E "Tests run|OK \(|FAILURES|Skipped" || true; } | tail -3
  if echo "$out" | grep -q "FAILURES!!!"; then
    echo "PHASE $method FAILED"; echo "$out" | grep -E "stack=|AssertionError" | head -5; exit 1
  fi
  if echo "$out" | grep -qE "Skipped: [1-9]"; then
    echo "PHASE $method SKIPPED — host pre-state was not in place; see the test's assume message"; exit 1
  fi
  echo "$out" | grep -q "OK (1 test)" || { echo "PHASE $method produced no clean result"; exit 1; }
  echo "phase $method PASSED"
}

# Plain `adb shell kill -9 <pid>` per PID: a nested `su 0 sh -c "..."` loses its
# quotes through the device shell and hits the toybox kill usage error.
kill_companion() {
  local pid
  for pid in $(companion_pidof); do
    $ADB shell su 0 kill -9 "$pid"
  done
  # Give the reaper a moment: a just-killed process can linger (zombie) in
  # `ps -A` for a beat, which would fake a "still alive" result.
  local i
  for i in 1 2 3 4 5 6; do
    [[ -z "$(companion_pidof)" ]] && return 0
    sleep 1
  done
}

# The companion must be NOT force-stopped for any of its components to run
# (a fresh `adb install` leaves it force-stopped; the repair entry is the only
# recovery path — root `am start` substitutes the user click). Leaves it
# healthy with NO process (kill, not force-stop).
warm_companion() {
  $ADB shell su 0 am force-stop "$COMPANION"
  $ADB shell su 0 am start -n "$COMPANION/.app.ProotRepairActivity" >/dev/null
  sleep 3
  # Background the repair Activity before killing: API 36 otherwise restores its top Activity.
  $ADB shell input keyevent KEYCODE_HOME
  sleep 1
  kill_companion
  [[ -z "$(companion_pidof)" ]] || { echo "companion process still alive after kill"; exit 1; }
}

# Every `am instrument` run force-stops the app under test when it finishes
# ("finished inst"); a force-stopped app fails the NEXT instrumentation with
# INSTRUMENTATION_FAILED. A plain `am start` (the main activity is not
# permission-protected) lifts it.
warm_main_app() {
  $ADB shell am start -n "$MAIN_APP/com.helix.app.MainActivity" >/dev/null
  sleep 3
}

# ------------------------------------------- phase 1: app startup never starts it
# Normalize the companion to a healthy (not force-stopped, not running) state.
warm_companion
warm_main_app
sleep 3
PIDS=$(companion_pidof)
[[ -z "$PIDS" ]] || { echo "APP STARTUP STARTED THE COMPANION: pid $PIDS"; exit 1; }
echo "app startup left the companion process absent"
run_phase "phaseAppStartupNeverStartsTheCompanionProcess"
[[ -z "$(companion_pidof)" ]] || { echo "the phase method started the companion"; exit 1; }

# ------------------------------------------- phase 2: force-stop stable state
$ADB shell su 0 am force-stop "$COMPANION"
run_phase "phaseForcedStoppedCompanionIsStablyUnavailable"
# Recover via the user-gated repair entry (the only recovery path for the
# force-stopped state), then leave no process behind.
kill_companion
sleep 1

# ------------------------------------------- phase 3: uninstalled (leaves it clean)
APK_LINE=$({ $ADB shell pm path "$COMPANION" || true; } | tr -d '\r' | cut -d: -f2)
[[ -n "$APK_LINE" ]] || { echo "companion not installed before phase 3"; exit 1; }
BACKUP="$(mktemp /tmp/helix-proot-companion.XXXXXX).apk"
$ADB pull "$APK_LINE" "$BACKUP" >/dev/null
$ADB shell pm uninstall "$COMPANION" >/dev/null
run_phase "phaseUninstalledCompanionIsStablyReported"
$ADB install -r "$BACKUP"
rm -f "$BACKUP"
$ADB shell pm list packages | grep -q "$COMPANION" || { echo "reinstall failed"; exit 1; }
# The reinstall leaves the companion force-stopped: warm it before the bind.
warm_companion

# ------------------------------------------- cold bind + SAFE suite end-to-end
# The companion is warm (warm_companion above) for all of these.
run_phase "coldBindHandshakeVerifiesTheLockConsistentDescriptor"
run_phase "aSecondVerificationChecksAgainstThePersistedAnchor"
run_phase "aProcessDeathIsDeadObjectAndTheColdRebindRecovers"
run_phase "theUserGatedRepairEntryOpensAndTheCompanionVerifies"
run_phase "aNullOnBindIsAnImmediateBindRefusedNotATimeout"
echo "HXA-083 lifecycle acceptance PASSED on $SERIAL (all 8 E2E methods)"
