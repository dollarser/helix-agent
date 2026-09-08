#!/usr/bin/env bash
# HXA-087 更新/卸载/法律页 — update/rollback/removal/legal acceptance
# (environment.md convention: method-level `am instrument -e class X#method`).
#
# Companion-side update-lifecycle suite (法律页 offline content, rollback,
# 完整删除 scoping, legal activity permission shape) runs as an ordinary
# instrumented class. The main-app-side E2E (user-click legal page open,
# removal anchor-clear + gate degradation + workspace survival, and the
# explicit re-baseline after a simulated lock mismatch) runs the full class —
# every method carries its own warm-companion assumes, and the script warms
# the companion + the main app's anchor (a real zero-Job verification) first.
#
# The REAL cross-APK update sequence (new companion APK with a moved embedded
# lock → 需更新 → repair-update → re-baseline) is the user's update path on a
# device with the new APK; the lock-mismatch → re-baseline half is exercised
# here with a simulated drift (same security property: the anchor pins the
# verified lock fingerprint; a moved baseline is never silently accepted).
#
# Usage:  scripts/accept-hxa-087-updates.sh [serial]
# Needs:  JAVA_HOME (or java on PATH), ANDROID_HOME (or adb on PATH).
set -euo pipefail

cd "$(dirname "$0")/.."
REPO_ROOT=$(pwd)
command -v java >/dev/null 2>&1 || { echo "java not found — set JAVA_HOME"; exit 1; }

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
COMPANION_RUNNER="com.helix.runtime.proot.test/androidx.test.runner.AndroidJUnitRunner"
UPDATE_CLASS="com.helix.app.proot.ProotUpdateLegalE2eDeviceTest"
COMPANION="com.helix.runtime.proot"
MAIN_APP="com.helix.agent.developer"

# ---------------------------------------------------------------- build + install
./gradlew :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest \
  :runtime:proot-app:assembleDebug :runtime:proot-app:assembleDebugAndroidTest
$ADB install -r app/build/outputs/apk/developer/debug/app-developer-debug.apk
$ADB install -r app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk
$ADB install -r runtime/proot-app/build/outputs/apk/debug/proot-app-debug.apk
$ADB install -r runtime/proot-app/build/outputs/apk/androidTest/debug/proot-app-debug-androidTest.apk

companion_pidof() { $ADB shell ps -A | { grep -w "$COMPANION" || true; } | awk '{print $2}' | tr -d $'\r'; }

kill_companion() {
  local pid
  for pid in $(companion_pidof); do
    $ADB shell su 0 kill -9 "$pid"
  done
  local i
  for i in 1 2 3 4 5 6; do
    [[ -z "$(companion_pidof)" ]] && return 0
    sleep 1
  done
  return 0
}

# Companion healthy (not force-stopped) with NO process: the user-gated repair
# entry (root `am start` substitutes the user click) lifts a force-stop, then
# the process is killed so the next transaction is a genuine cold bind.
warm_companion() {
  $ADB shell su 0 am force-stop "$COMPANION"
  $ADB shell su 0 am start -n "$COMPANION/.app.ProotRepairActivity" >/dev/null
  sleep 3
  kill_companion
  [[ -z "$(companion_pidof)" ]] || { echo "companion process still alive after kill"; exit 1; }
}

warm_main_app() {
  $ADB shell am start -n "$MAIN_APP/com.helix.app.MainActivity" >/dev/null
  sleep 3
}

# A real zero-Job verification on the main app: persists the anchor the
# removal / re-baseline tests build on (their @After restores it — the script
# re-verifies between phases anyway, so drift is self-healing).
ensure_anchor() {
  echo "==> ensure anchor (zero-Job verification)"
  local out
  out=$($ADB shell am instrument -w -r \
    -e class "com.helix.app.proot.ProotRuntimeBindingE2eDeviceTest#coldBindHandshakeVerifiesTheLockConsistentDescriptor" \
    "$RUNNER" 2>&1) || true
  echo "$out" | { grep -E "Tests run|OK \(|FAILURES|Skipped" || true; } | tail -3
  echo "$out" | grep -q "OK (1 test)" || { echo "ANCHOR VERIFICATION FAILED"; echo "$out" | grep -E "stack=|AssertionError" | head -5; exit 1; }
  echo "anchor verified"
}

# =============== phase 1: companion update-lifecycle suite ===============
# (法律页 offline content + rollback + 完整删除 scoping; installs its own
# runtime state as needed; ~2 real installs of 131 MiB — a few minutes)
echo "==> companion phase: ProotUpdateLifecycleDeviceTest (full class)"
out=$($ADB shell am instrument -w -r -e class "com.helix.runtime.proot.app.ProotUpdateLifecycleDeviceTest" "$COMPANION_RUNNER" 2>&1) || true
echo "$out" | { grep -E "Tests run|OK \(|FAILURES|Skipped" || true; } | tail -3
if echo "$out" | grep -q "FAILURES!!!"; then
  echo "COMPANION UPDATE SUITE FAILED"; echo "$out" | grep -E "stack=|AssertionError" | head -8; exit 1
fi
if echo "$out" | grep -qE "Skipped: [1-9]"; then
  echo "COMPANION UPDATE SUITE SKIPPED — see the test's assume message"; exit 1
fi
echo "$out" | grep -q "OK (7 tests)" || { echo "COMPANION UPDATE SUITE produced no clean result"; exit 1; }
echo "companion update-lifecycle suite PASSED"

# =============== phase 2: main-app-side E2E (legal page / removal / re-baseline)
warm_companion
warm_main_app
ensure_anchor
echo "==> app phase: $UPDATE_CLASS (full class)"
out=$($ADB shell am instrument -w -r -e class "$UPDATE_CLASS" "$RUNNER" 2>&1) || true
echo "$out" | { grep -E "Tests run|OK \(|FAILURES|Skipped" || true; } | tail -3
if echo "$out" | grep -q "FAILURES!!!"; then
  echo "APP UPDATE E2E FAILED"; echo "$out" | grep -E "stack=|AssertionError" | head -8; exit 1
fi
if echo "$out" | grep -qE "Skipped: [1-9]"; then
  echo "APP UPDATE E2E SKIPPED — see the test's assume message"; exit 1
fi
echo "$out" | grep -q "OK (4 tests)" || { echo "APP UPDATE E2E produced no clean result"; exit 1; }
echo "app-side update E2E PASSED"

echo "HXA-087 update/removal/legal acceptance PASSED on $SERIAL"
