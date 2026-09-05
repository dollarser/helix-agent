#!/usr/bin/env bash
# HXA-086 真机 smoke 与隔离 — lifecycle/isolation acceptance (environment.md
# convention: method-level `am instrument -e class X#method`).
#
# Companion-side suites (smoke python/node/git/ripgrep, isolation, 通知停止)
# run as ordinary instrumented classes. The lifecycle phases need host-side
# states an app process cannot create (fresh-install force-stop, idle-kill,
# screen-off, low-memory kill, main-app death mid-job, companion death
# mid-job, wake-lock sampling); this script creates those states on the
# target device and drives each phase.
#
# Real-device gaps (NO real arm64 devices in this environment — recorded in
# docs/completion-records/HXA-086.md, never faked): Doze (no `device_idle`
# service on this emulator), secure keyguard/锁屏, 热限 (thermal), and the
# 4 KiB/16 KiB REAL-device smoke matrix (this script is the 4 KiB-emulator
# half; 16 KiB compatibility is structurally gated by the HXA-081 alignment
# gate + the installer's per-page-size pre-check that blocks distribution).
#
# Usage:  scripts/accept-hxa-086-lifecycle.sh [serial]
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
HOST_SETUP_CLASS="com.helix.runtime.proot.app.ProotLifecycleHostSetupDeviceTest#activeRuntimeIsInstalledForHostPhases"
LIFECYCLE_CLASS="com.helix.app.proot.ProotLifecycleE2eDeviceTest"
ISOLATION_CLASS="com.helix.app.proot.ProotRuntimeIsolationE2eDeviceTest"
COMPANION="com.helix.runtime.proot"
MAIN_APP="com.helix.agent.developer"

# ---------------------------------------------------------------- build + install
./gradlew :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest \
  :runtime:proot-app:assembleDebug :runtime:proot-app:assembleDebugAndroidTest
$ADB install -r app/build/outputs/apk/developer/debug/app-developer-debug.apk
$ADB install -r app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk
$ADB install -r runtime/proot-app/build/outputs/apk/debug/proot-app-debug.apk
$ADB install -r runtime/proot-app/build/outputs/apk/androidTest/debug/proot-app-debug-androidTest.apk
# 通知权限 (API 33+); no-op / harmless failure on older platforms.
$ADB shell pm grant "$COMPANION" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

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

# Run one app-side phase method. The method name comes FIRST; extra
# `-e key value` pairs follow (the host-bracketed phases take their marker
# that way).
run_phase() {
  local method="$1"; shift
  local -a instr_args=("$@")
  echo "==> phase: $method"
  warm_main_app
  local out
  if [[ ${#instr_args[@]} -gt 0 ]]; then
    out=$($ADB shell am instrument -w -r -e hxa086_host_phase 1 "${instr_args[@]}" -e class "$LIFECYCLE_CLASS#$method" "$RUNNER" 2>&1) || true
  else
    out=$($ADB shell am instrument -w -r -e hxa086_host_phase 1 -e class "$LIFECYCLE_CLASS#$method" "$RUNNER" 2>&1) || true
  fi
  echo "$out" | { grep -E "Tests run|OK \(|FAILURES|Skipped" || true; } | tail -3
  if echo "$out" | grep -q "FAILURES!!!"; then
    echo "PHASE FAILED"; echo "$out" | grep -E "stack=|AssertionError" | head -5; exit 1
  fi
  if echo "$out" | grep -qE "Skipped: [1-9]"; then
    echo "PHASE SKIPPED — host pre-state was not in place; see the test's assume message"; exit 1
  fi
  echo "$out" | grep -q "OK (1 test)" || { echo "PHASE produced no clean result"; exit 1; }
  echo "phase PASSED"
}

run_companion_phase() {
  local class="$1"; shift
  local -a instr_args=("$@")
  echo "==> companion phase: $class"
  local out
  if [[ ${#instr_args[@]} -gt 0 ]]; then
    out=$($ADB shell am instrument -w -r "${instr_args[@]}" -e class "$class" "$COMPANION_RUNNER" 2>&1) || true
  else
    out=$($ADB shell am instrument -w -r -e class "$class" "$COMPANION_RUNNER" 2>&1) || true
  fi
  echo "$out" | { grep -E "Tests run|OK \(|FAILURES|Skipped" || true; } | tail -3
  if echo "$out" | grep -q "FAILURES!!!"; then
    echo "COMPANION PHASE $class FAILED"; echo "$out" | grep -E "stack=|AssertionError" | head -5; exit 1
  fi
  if echo "$out" | grep -qE "Skipped: [1-9]"; then
    echo "COMPANION PHASE $class SKIPPED — see the test's assume message"; exit 1
  fi
  echo "$out" | grep -q "OK (" || { echo "COMPANION PHASE $class produced no clean result"; exit 1; }
  echo "companion phase PASSED"
}

# APK installation and lifting force-stop do not install the embedded RootFS.
# Establish the real active-runtime precondition once; lifecycle phases below
# then vary only package/process state and execute through the cross-APK path.
run_companion_phase "$HOST_SETUP_CLASS" -e hxa086_host_setup 1

# Read the LIFECYCLE-JOB-ID marker the long-job phases write into the app's
# filesDir (same uid as the companion — host-readable).
job_id_of() {
  $ADB shell run-as "$MAIN_APP" cat files/LIFECYCLE-JOB-ID 2>/dev/null | tr -d $'\r'
}

# =============== phase 0: installed companion package (force-stopped) ===========
# A force-stopped companion must be refused stably; only the user-gated repair
# entry may lift the stopped-package state. Runtime installation above is an
# independent prerequisite and must not be confused with process/package state.
$ADB shell su 0 am force-stop "$COMPANION"
run_phase phaseForceStoppedCompanionIsStablyRefusedForJobs

# The user's recovery action: the repair entry (warm_companion), then the
# companion is left healthy with no process — the NEXT transaction is the
# cold bind of a never-opened-this-session runtime.
warm_companion

# =============== phase 1: cold bind (never-opened this session) ===============
run_phase phaseFirstJobAfterCleanStateRunsByColdBind

# =============== phase 2: idle recycle (process reaped, state healthy) =========
kill_companion
sleep 2
run_phase phaseFirstJobAfterCleanStateRunsByColdBind

# =============== phase 3: screen off (inattentive) ===============
$ADB shell input keyevent 26
sleep 2
run_phase phaseFirstJobAfterCleanStateRunsByColdBind
$ADB shell input keyevent 26
sleep 2

# NOTE: Doze (device_idle) and the secure keyguard (锁屏) are NOT reachable on
# this emulator (no `device_idle` service; no secure lock setup). They are
# real-device matrix items — recorded as the HXA-086 real-device gap, never
# faked here.

# =============== phase 4: low-memory (main app background-killed) ===============
warm_main_app
sleep 2
$ADB shell am kill "$MAIN_APP" >/dev/null
sleep 2
run_phase phaseFirstJobAfterCleanStateRunsByColdBind

# =============== phase 5: main-app death MID JOB (unbind → orphan) ============
warm_companion
warm_main_app
LONG_OUT=$($ADB shell am instrument -w -r \
  -e hxa086_host_phase 1 \
  -e class "$LIFECYCLE_CLASS#phaseLongJobThenReturnWithJobId" "$RUNNER" 2>&1) || true
echo "$LONG_OUT" | { grep -E "OK \(|FAILURES|Skipped" || true; } | tail -2
echo "$LONG_OUT" | grep -q "OK (1 test)" || { echo "LONG JOB PHASE produced no clean result"; exit 1; }
ORPHAN_JOB=$(job_id_of)
[[ -n "$ORPHAN_JOB" ]] || { echo "no LIFECYCLE-JOB-ID marker after the long-job phase"; exit 1; }
echo "long job: $ORPHAN_JOB (main app force-stopped by instrumentation → unbind → orphan)"
sleep 5
# The companion is re-warmed through the user-gated repair entry; the next
# service start sweeps the orphan (pid+starttime double check).
warm_companion
out=$($ADB shell am instrument -w -r -e hxa086_host_phase 1 -e class "$LIFECYCLE_CLASS#phaseQueryTheOrphanedJobByJobId" \
  -e job_id "$ORPHAN_JOB" "$RUNNER" 2>&1) || true
echo "$out" | { grep -E "Tests run|OK \(|FAILURES|Skipped" || true; } | tail -3
echo "$out" | grep -q "OK (1 test)" || { echo "ORPHAN QUERY FAILED"; echo "$out" | grep -E "stack=|AssertionError" | head -5; exit 1; }
echo "main-app-death-mid-job → ORPHANED reconciliation PASSED"

# =============== phase 6: companion death MID JOB (binder → DEAD_OBJECT) ======
warm_companion
warm_main_app
( sleep 6; kill_companion ) &
KILL_PID=$!
out=$($ADB shell am instrument -w -r \
  -e hxa086_host_phase 1 \
  -e companion_kill 1 \
  -e class "$LIFECYCLE_CLASS#phaseAwaitJobWhileTheHostKillsTheCompanion" "$RUNNER" 2>&1) || true
wait "$KILL_PID" 2>/dev/null || true
echo "$out" | { grep -E "Tests run|OK \(|FAILURES|Skipped" || true; } | tail -3
echo "$out" | grep -q "OK (1 test)" || { echo "COMPANION KILL AWAIT FAILED"; echo "$out" | grep -E "stack=|AssertionError" | head -5; exit 1; }
KILLED_JOB=$(job_id_of)
[[ -n "$KILLED_JOB" ]] || { echo "no LIFECYCLE-JOB-ID marker after the await-kill phase"; exit 1; }
echo "killed mid-job: $KILLED_JOB (client settled stable non-success: DEAD_OBJECT or ORPHANED)"
warm_companion
out=$($ADB shell am instrument -w -r -e hxa086_host_phase 1 -e class "$LIFECYCLE_CLASS#phaseQueryTheOrphanedJobByJobId" \
  -e job_id "$KILLED_JOB" "$RUNNER" 2>&1) || true
echo "$out" | { grep -E "Tests run|OK \(|FAILURES|Skipped" || true; } | tail -3
echo "$out" | grep -q "OK (1 test)" || { echo "KILLED-JOB ORPHAN QUERY FAILED"; echo "$out" | grep -E "stack=|AssertionError" | head -5; exit 1; }
echo "companion-death-mid-job → DEAD_OBJECT + ORPHANED reconciliation PASSED"

# =============== phase 7: wake-lock / FGS leak sampling during a job ===========
warm_companion
COMPANION_UID=$($ADB shell dumpsys package "$COMPANION" | grep -o "uid=[0-9]*" | head -1 | cut -d= -f2 | tr -d $'\r')
[[ -n "$COMPANION_UID" ]] || { echo "could not resolve the companion uid"; exit 1; }
echo "companion uid: $COMPANION_UID"
SAMPLE_DIR=$(mktemp -d /tmp/helix-proot-power.XXXXXX)
(
  for i in 1 2 3 4 5 6 7 8; do
    $ADB shell dumpsys power > "$SAMPLE_DIR/power-$i" 2>/dev/null || true
    sleep 2
  done
) &
SAMPLER=$!
run_phase phaseLongJobForWakelockSampling
wait "$SAMPLER" 2>/dev/null || true
LEAKS=$(cat "$SAMPLE_DIR"/power-* 2>/dev/null | awk '
  /Wake Locks: size=/{wl=1; next}
  wl && /^[^ ]/ {wl=0}
  /mForegroundServices/ || /Foreground Services/{fgs=1}
  wl || fgs ? $0 : ""
' | { grep "uid=$COMPANION_UID" || true; })
rm -rf "$SAMPLE_DIR"
if [[ -n "$LEAKS" ]]; then
  echo "WAKE-LOCK/FGS LEAK for the companion uid: $LEAKS"; exit 1
fi
echo "no wake lock or FGS for the companion uid across the job window"

# =============== phase 8: duplicate job id (HXA-084 regression in context) =====
run_companion_phase "com.helix.runtime.proot.app.ProotJobRunnerDeviceTest#aJobIsStartedExactlyOnce"

# =============== phase 9: companion suites (smoke / isolation / 通知停止) =======
run_companion_phase "com.helix.runtime.proot.app.ProotSmokeDeviceTest"
run_companion_phase "com.helix.runtime.proot.app.ProotIsolationDeviceTest"
run_companion_phase "com.helix.runtime.proot.app.ProotJobNotificationDeviceTest"

# =============== phase 10: main-app-side isolation through the real tool =======
# The guest of a real `code.linux.run` execution cannot read/write the main
# app's dataDir (the companion-side ProotIsolationDeviceTest covers the
# companion's own dataDir + shared storage + network).
echo "==> phase: $ISOLATION_CLASS (full class)"
warm_main_app
out=$($ADB shell am instrument -w -r -e class "$ISOLATION_CLASS" "$RUNNER" 2>&1) || true
echo "$out" | { grep -E "Tests run|OK \(|FAILURES|Skipped" || true; } | tail -3
if echo "$out" | grep -q "FAILURES!!!"; then
  echo "ISOLATION E2E FAILED"; echo "$out" | grep -E "stack=|AssertionError" | head -5; exit 1
fi
if echo "$out" | grep -qE "Skipped: [1-9]"; then
  echo "ISOLATION E2E SKIPPED — see the test's assume message"; exit 1
fi
echo "$out" | grep -q "OK (2 tests)" || { echo "ISOLATION E2E produced no clean result"; exit 1; }
echo "main-app-side isolation PASSED"

echo "HXA-086 lifecycle acceptance PASSED on $SERIAL"
