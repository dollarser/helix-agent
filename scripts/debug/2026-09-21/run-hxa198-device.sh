#!/bin/sh
# HXA-198 multi-session terminal device acceptance: API 29 & API 36
# Dual-session concurrency, 3rd session rejection, observer mode isolation,
# agent job mutual exclusion, 20-cycle lifecycle soak, and crash recovery.
set -eu
ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")/../../.." && pwd)
cd "$ROOT_DIR"
: "${JAVA_HOME:?Set JAVA_HOME to the JDK home}"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
RUNNER=scripts/debug/2026-09-09/run-owned-emulator.py
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
p = int(sys.argv[1])
for port in (p, p + 1):
    s = socket.socket()
    try:
        s.bind(("127.0.0.1", port))
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

classes="com.helix.app.proot.ProotMultiSessionDeviceTest,com.helix.app.proot.ProotTerminalSessionDeviceTest"
main_pkg="com.helix.agent.developer"
test_pkg="com.helix.agent.developer.test"

for target in "36 5554" "29 5556"; do
  set -- $target
  api=$1
  port=$2
  out="build/evidence/hxa198-device/developer-api$api"
  rm -rf "$out"
  mkdir -p "$(dirname "$out")"
  echo "== [$(date -u +%H:%M:%S)] Starting HXA-198 developer API $api on port $port"
  wait_runnable "$port" || { overall=1; break; }
  python3 "$RUNNER" \
    --avd "Helix_M11_Test_API_$api" --port "$port" \
    --apk "app/build/outputs/apk/developer/debug/app-developer-debug.apk" \
    --test-apk "app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk" \
    --runner "$test_pkg/com.helix.app.HelixAndroidJUnitRunner" \
    --classes "$classes" \
    --output "$out" \
    --timeout 600 || { echo "FAIL developer api$api hxa198"; overall=1; break; }
  echo "== [$(date -u +%H:%M:%S)] PASSED developer API $api"
done

echo "== HXA-198 device suite finished overall=$overall"
exit $overall
