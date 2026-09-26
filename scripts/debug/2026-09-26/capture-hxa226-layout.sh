#!/usr/bin/env bash
# HXA-226 simulator verification — drive the layout-capture fixture classes and pull the
# synthetic screenshots out of the app's cache dir.
#
# Usage: capture-hxa226-layout.sh <out-dir> [language] [flavor]
#   language: en | zh-CN   (default zh-CN)
#   flavor:   consumer | developer (default consumer)
#
# The capture path is gated behind `-e helix.layout.capture true`, so a normal suite run
# produces nothing. ChatLayoutCapture writes PNGs to cacheDir/hxa147-layout/ and the host
# pulls them back with `adb exec-out run-as <pkg> cat`.
#
# Each class is pulled immediately after it runs: `pm clear` between classes would wipe
# cacheDir and silently discard every earlier capture.
set -uo pipefail

OUT_DIR="${1:?usage: capture-hxa226-layout.sh <out-dir> [language] [flavor]}"
LANGUAGE="${2:-zh-CN}"
FLAVOR="${3:-consumer}"

SERIAL="${SERIAL:-emulator-5554}"
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"

case "$FLAVOR" in
  consumer) PKG="com.helix.agent" ;;
  developer) PKG="com.helix.agent.developer" ;;
  *) echo "unknown flavor $FLAVOR" >&2; exit 2 ;;
esac
RUNNER="$PKG.test/com.helix.app.HelixAndroidJUnitRunner"

mkdir -p "$OUT_DIR/screenshots"

# These are the classes that call captureChatLayout(...) under the HXA-226 scope.
CLASSES=(
  com.helix.app.ui.NavigationLayoutDeviceTest
  com.helix.app.ui.ConversationTopBarDeviceTest
  com.helix.app.ui.ChatStopProgressDeviceTest
  com.helix.app.ui.ConversationArtifactsDeviceTest
)

for cls in "${CLASSES[@]}"; do
  log="$OUT_DIR/${cls}.log"
  echo "== $cls"
  # `adb shell` forwards its own stdin into the remote shell; close it or the loop starves.
  adb -s "$SERIAL" shell pm clear "$PKG" >/dev/null 2>&1 </dev/null
  adb -s "$SERIAL" shell am instrument -w -r \
    -e class "$cls" \
    -e helix.test.language "$LANGUAGE" \
    -e helix.layout.capture true \
    "$RUNNER" >"$log" 2>&1 </dev/null
  grep -E '^(OK \(|FAILURES!!!|INSTRUMENTATION_FAILED)' "$log" | tail -1

  names=$(adb -s "$SERIAL" exec-out run-as "$PKG" ls -1 cache/hxa147-layout 2>/dev/null </dev/null)
  if [ -z "$names" ]; then
    echo "   (no captures)"
    continue
  fi
  while IFS= read -r name; do
    [ -z "$name" ] && continue
    adb -s "$SERIAL" exec-out run-as "$PKG" cat "cache/hxa147-layout/$name" \
      >"$OUT_DIR/screenshots/$name" 2>/dev/null </dev/null
    printf '   %-34s %s bytes\n' "$name" "$(wc -c <"$OUT_DIR/screenshots/$name" | tr -d ' ')"
  done <<<"$names"
done

echo "captured $(ls -1 "$OUT_DIR/screenshots" | wc -l | tr -d ' ') screenshots -> $OUT_DIR/screenshots"
