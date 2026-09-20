#!/bin/sh
# HXA-204 slice 4 device matrix — four quadrants (flavor x api), two runs each on
# exclusive 1080x2400@420 emulators (the owned runner forces -read-only pristine base,
# refuses a reused serial, closes only its own process group, and the after-script
# uninstalls both packages before shutdown):
#   run 1: the new com.helix.app.RecoveryJourneyDeviceTest through the two-phase
#          recoveryPhase protocol (the runner's recovery-setup-class seeds the five
#          persisted failure facts and kills the process; the main run verifies a new PID,
#          zero re-execution, and every real recovery entry point) + the HXA-194/203-era
#          per-session regressions com.helix.app.ui.ChatStopProgressDeviceTest (the
#          explicit-retry journey) and com.helix.app.chat.ToolResultReadDeviceTest — both
#          assert only against their own sessions/fixed ids, so they share this install;
#   run 2: the HXA-202 TaskJourneyDeviceTest regression ALONE on a fresh installation —
#          its global totalTurns baselines must not share an install with other
#          fixture-seeding classes, and its two-phase recovery protocol needs the
#          runner's recovery-setup-class support.
set -eu
ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")/../../../.." && pwd)
cd "$ROOT_DIR"
: "${JAVA_HOME:?Set JAVA_HOME to the JDK home}"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
RUNNER=scripts/debug/2026-09-09/run-owned-emulator.py
CLEAN=scripts/debug/2026-09-17/hxa194/clean-packages.py
ADB="$ANDROID_HOME/platform-tools/adb"
overall=0
wait_runnable() {
  i=0
  while :; do
    i=$((i+1))
    if [ "$i" -gt 60 ]; then echo "FAIL preconditions for emulator-$1 unmet after 120s"; return 1; fi
    if "$ADB" devices | grep -q "^emulator-$1[[:space:]]"; then sleep 2; continue; fi
    if python3 -c '
import socket, sys
for p in (int(sys.argv[1]), int(sys.argv[1]) + 1):
    s = socket.socket()
    try:
        s.bind(("127.0.0.1", p))
    except OSError:
        sys.exit(1)
    finally:
        s.close()
' "$1"; then
      return 0
    fi
    sleep 2
  done
}
for quadrant in "consumer 36 5554" "consumer 29 5556" "developer 36 5558" "developer 29 5560"; do
  set -- $quadrant
  variant=$1 api=$2 port=$3
  suffix=
  if [ "$variant" = developer ]; then suffix=.developer; fi
  main_pkg="com.helix.agent$suffix"
  test_pkg="com.helix.agent$suffix.test"
  recovery_classes="com.helix.app.RecoveryJourneyDeviceTest,com.helix.app.ui.ChatStopProgressDeviceTest,com.helix.app.chat.ToolResultReadDeviceTest"
  echo "== [$(date -u +%H:%M:%S)] $variant api$api hxa204 recovery journey + regressions: $recovery_classes"
  wait_runnable "$port" || { overall=1; break; }
  CLEAN_PKG_MAIN="$main_pkg" CLEAN_PKG_TEST="$test_pkg" python3 "$RUNNER" \
    --avd "Helix_M11_Test_API_$api" --port "$port" \
    --apk "app/build/outputs/apk/$variant/debug/app-$variant-debug.apk" \
    --test-apk "app/build/outputs/apk/androidTest/$variant/debug/app-$variant-debug-androidTest.apk" \
    --runner "$test_pkg/com.helix.app.HelixAndroidJUnitRunner" \
    --classes "$recovery_classes" \
    --recovery-setup-class "com.helix.app.RecoveryJourneyDeviceTest" \
    --instrument-arg recoveryPhase=verify \
    --output "app/build/hxa204-device/$variant-api$api-recovery" \
    --after-script "$CLEAN" || { echo "FAIL $variant api$api recovery"; overall=1; break; }
  echo "== [$(date -u +%H:%M:%S)] $variant api$api taskjourney regression (fresh install)"
  wait_runnable "$port" || { overall=1; break; }
  CLEAN_PKG_MAIN="$main_pkg" CLEAN_PKG_TEST="$test_pkg" python3 "$RUNNER" \
    --avd "Helix_M11_Test_API_$api" --port "$port" \
    --apk "app/build/outputs/apk/$variant/debug/app-$variant-debug.apk" \
    --test-apk "app/build/outputs/apk/androidTest/$variant/debug/app-$variant-debug-androidTest.apk" \
    --runner "$test_pkg/com.helix.app.HelixAndroidJUnitRunner" \
    --classes "com.helix.app.TaskJourneyDeviceTest" \
    --recovery-setup-class "com.helix.app.TaskJourneyDeviceTest" \
    --instrument-arg recoveryPhase=verify \
    --output "app/build/hxa204-device/$variant-api$api-taskjourney" \
    --after-script "$CLEAN" || { echo "FAIL $variant api$api taskjourney"; overall=1; break; }
done
echo "== matrix finished overall=$overall"
exit $overall
