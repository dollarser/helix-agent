#!/bin/sh
# HXA-205 slice 3 device matrix — four quadrants (flavor x api). The shared flavor-agnostic
# journey com.helix.app.ui.CapabilityReadinessDeviceTest runs in EVERY quadrant through the
# two-phase recoveryPhase protocol (the runner's recovery-setup-class records the process id
# and kills the process; the main run verifies a new PID and a passive re-render from durable
# facts), under airplane mode so the offline facet is genuinely offline (no cold bind / login).
# The developer flavor additionally runs the Runtime-specific
# com.helix.app.proot.CapabilityReadinessRuntimeDeviceTest (actual init reusing the HXA-193
# verify entry + the corrupted-anchor repair) standalone on a fresh install.
#
# The owned runner forces a -read-only pristine base, refuses a reused serial, closes only its
# own process group, and the after-script uninstalls both packages before shutdown — so every
# invocation below starts from a clean installation (the developer shared run therefore sees the
# runtime NOT_INSTALLED, and the developer runtime run installs it itself).
set -eu
ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")/../../../.." && pwd)
cd "$ROOT_DIR"
: "${JAVA_HOME:?Set JAVA_HOME to the JDK home}"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
RUNNER=scripts/debug/2026-09-09/run-owned-emulator.py
CLEAN=scripts/debug/2026-09-17/hxa194/clean-packages.py
ADB="$ANDROID_HOME/platform-tools/adb"
SHARED_CLASS=com.helix.app.ui.CapabilityReadinessDeviceTest
RUNTIME_CLASS=com.helix.app.proot.CapabilityReadinessRuntimeDeviceTest
# The runner requires the --output directory to NOT exist (exist_ok=False); clear any prior
# round of this matrix so a re-run does not hit FileExistsError.
rm -rf app/build/hxa205-device
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
  echo "== [$(date -u +%H:%M:%S)] $variant api$api capability readiness journey (two-phase, offline): $SHARED_CLASS"
  wait_runnable "$port" || { overall=1; break; }
  CLEAN_PKG_MAIN="$main_pkg" CLEAN_PKG_TEST="$test_pkg" python3 "$RUNNER" \
    --avd "Helix_M11_Test_API_$api" --port "$port" \
    --apk "app/build/outputs/apk/$variant/debug/app-$variant-debug.apk" \
    --test-apk "app/build/outputs/apk/androidTest/$variant/debug/app-$variant-debug-androidTest.apk" \
    --runner "$test_pkg/com.helix.app.HelixAndroidJUnitRunner" \
    --classes "$SHARED_CLASS" \
    --recovery-setup-class "$SHARED_CLASS" \
    --instrument-arg recoveryPhase=verify \
    --airplane-mode \
    --output "app/build/hxa205-device/$variant-api$api-journey" \
    --after-script "$CLEAN" || { echo "FAIL $variant api$api journey"; overall=1; break; }
  if [ "$variant" = developer ]; then
    echo "== [$(date -u +%H:%M:%S)] $variant api$api capability readiness runtime (actual init + repair): $RUNTIME_CLASS"
    wait_runnable "$port" || { overall=1; break; }
    CLEAN_PKG_MAIN="$main_pkg" CLEAN_PKG_TEST="$test_pkg" python3 "$RUNNER" \
      --avd "Helix_M11_Test_API_$api" --port "$port" \
      --apk "app/build/outputs/apk/$variant/debug/app-$variant-debug.apk" \
      --test-apk "app/build/outputs/apk/androidTest/$variant/debug/app-$variant-debug-androidTest.apk" \
      --runner "$test_pkg/com.helix.app.HelixAndroidJUnitRunner" \
      --classes "$RUNTIME_CLASS" \
      --output "app/build/hxa205-device/$variant-api$api-runtime" \
      --after-script "$CLEAN" || { echo "FAIL $variant api$api runtime"; overall=1; break; }
  fi
done
echo "== matrix finished overall=$overall"
exit $overall
