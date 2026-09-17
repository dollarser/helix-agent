#!/bin/sh
# HXA-194 slice 3 device acceptance — four quadrants (flavor x api), two runs each on
# exclusive 1080x2400@420 emulators (-read-only pristine base, own serial, closed by the
# runner, both packages uninstalled before close):
#   1. the HXA-194 classes (developer flavor adds the browse quadrant class);
#   2. the HXA-202 TaskJourneyDeviceTest regression ALONE on a fresh installation — its
#      global totalTurns baselines must not share an install with other fixture-seeding
#      classes, and its two-phase recovery protocol (setup kills the process) needs the
#      owned runner's recovery-setup-class support.
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
# The runner kills the emulator's session in finally, but afterwards the adb serial can
# linger (offline) while the console port is still bound — and vice versa. The runner's
# own preconditions are BOTH: serial absent from `adb devices` (its line 27 check) and
# both console ports bindable (its line 33 check), so wait for the union.
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
  classes194="com.helix.app.CommandExecutionDetailsDeviceTest"
  if [ "$variant" = developer ]; then
    classes194="$classes194,com.helix.app.proot.CommandResultBrowseDeviceTest"
  fi
  main_pkg="com.helix.agent$suffix"
  test_pkg="com.helix.agent$suffix.test"
  echo "== [$(date -u +%H:%M:%S)] $variant api$api hxa194 classes: $classes194"
  wait_runnable "$port" || { overall=1; break; }
  CLEAN_PKG_MAIN="$main_pkg" CLEAN_PKG_TEST="$test_pkg" python3 "$RUNNER" \
    --avd "Helix_M11_Test_API_$api" --port "$port" \
    --apk "app/build/outputs/apk/$variant/debug/app-$variant-debug.apk" \
    --test-apk "app/build/outputs/apk/androidTest/$variant/debug/app-$variant-debug-androidTest.apk" \
    --runner "$test_pkg/com.helix.app.HelixAndroidJUnitRunner" \
    --classes "$classes194" --output "app/build/hxa194-device/$variant-api$api-194" \
    --after-script "$CLEAN" || { echo "FAIL $variant api$api hxa194"; overall=1; break; }
  echo "== [$(date -u +%H:%M:%S)] $variant api$api taskjourney regression"
  wait_runnable "$port" || { overall=1; break; }
  CLEAN_PKG_MAIN="$main_pkg" CLEAN_PKG_TEST="$test_pkg" python3 "$RUNNER" \
    --avd "Helix_M11_Test_API_$api" --port "$port" \
    --apk "app/build/outputs/apk/$variant/debug/app-$variant-debug.apk" \
    --test-apk "app/build/outputs/apk/androidTest/$variant/debug/app-$variant-debug-androidTest.apk" \
    --runner "$test_pkg/com.helix.app.HelixAndroidJUnitRunner" \
    --classes "com.helix.app.TaskJourneyDeviceTest" \
    --recovery-setup-class "com.helix.app.TaskJourneyDeviceTest" \
    --instrument-arg recoveryPhase=verify \
    --output "app/build/hxa194-device/$variant-api$api-taskjourney" \
    --after-script "$CLEAN" || { echo "FAIL $variant api$api taskjourney"; overall=1; break; }
done
echo "== matrix finished overall=$overall"
exit $overall
