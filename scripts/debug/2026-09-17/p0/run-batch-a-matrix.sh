#!/bin/sh
# P0 + Batch A integration regression matrix — four quadrants (flavor x api), two runs each on
# exclusive 1080x2400@420 emulators (the owned runner forces -read-only pristine base,
# refuses a reused serial, closes only its own process group, and the after-script
# uninstalls both packages before shutdown):
#   run 1: com.helix.app.ArtifactDeliveryDeviceTest (the 203 delivery loop + batch-A exit
#          journey) + the HXA-194 CommandExecutionDetailsDeviceTest regression (the journey
#          reuses the 194 stable call identity) — the developer flavor adds the browse class;
#   run 2: the HXA-202 TaskJourneyDeviceTest regression ALONE on a fresh installation — its
#          global totalTurns baselines must not share an install with other fixture-seeding
#          classes, and its two-phase recovery protocol needs the runner's
#          recovery-setup-class support.
set -eu
cd "$(dirname "$0")/../../../.."
export ANDROID_HOME="${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
RUNNER=scripts/debug/2026-09-09/run-owned-emulator.py
CLEAN=scripts/debug/2026-09-17/p0/clean-packages.py
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
  classes203="com.helix.app.ArtifactDeliveryDeviceTest,com.helix.app.CommandExecutionDetailsDeviceTest"
  if [ "$variant" = developer ]; then
    classes203="$classes203,com.helix.app.proot.CommandResultBrowseDeviceTest"
  fi
  main_pkg="com.helix.agent$suffix"
  test_pkg="com.helix.agent$suffix.test"
  echo "== [$(date -u +%H:%M:%S)] $variant api$api hxa203 + 194 classes: $classes203"
  wait_runnable "$port" || { overall=1; break; }
  CLEAN_PKG_MAIN="$main_pkg" CLEAN_PKG_TEST="$test_pkg" python3 "$RUNNER" \
    --avd "Helix_M11_Test_API_$api" --port "$port" \
    --apk "app/build/outputs/apk/$variant/debug/app-$variant-debug.apk" \
    --test-apk "app/build/outputs/apk/androidTest/$variant/debug/app-$variant-debug-androidTest.apk" \
    --runner "$test_pkg/com.helix.app.HelixAndroidJUnitRunner" \
    --classes "$classes203" --output "build/p0-batch-a-integration/$variant-api$api-203" \
    --after-script "$CLEAN" || { echo "FAIL $variant api$api hxa203+194"; overall=1; break; }
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
    --output "build/p0-batch-a-integration/$variant-api$api-taskjourney" \
    --after-script "$CLEAN" || { echo "FAIL $variant api$api taskjourney"; overall=1; break; }
done
echo "== matrix finished overall=$overall"
exit $overall
