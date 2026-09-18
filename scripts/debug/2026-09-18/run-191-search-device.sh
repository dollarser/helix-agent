#!/usr/bin/env bash
# HXA-191 search slice: ONE device quadrant per invocation
# (consumer|developer x API29|API36), on its OWN AVD (Helix191_API*) and a
# unique even console port, via scripts/debug/2026-09-09/run-owned-emulator.py:
#   1. recovery setup instrument run: seeds the fixed-id fixture, records the
#      process id and kills the process ("Process crashed" is expected);
#   2. main instrument run with recoveryPhase=verify: the restart test asserts
#      a new PID sees the persisted seeds, then the six UI facet methods run;
#   3. after-script: the storage connected query test on the same owned serial.
# The runner tears down only its own process group. Output lands in the given
# output dir under build/ (ignored).
#
# Usage: run-191-search-device.sh <consumer|developer> <29|36> <port> <output-dir>
#   e.g. run-191-search-device.sh consumer 36 5642 build/hxa-191-matrix-consumer-api36
set -euo pipefail
readonly repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$repo_root"

VARIANT="${1:?consumer|developer}"
API="${2:?api level 29 or 36}"
PORT="${3:?even console port}"
OUT="${4:?output dir}"

case "$VARIANT" in
    consumer) SUFFIX="" ;;
    developer) SUFFIX=".developer" ;;
    *) echo "unknown variant: $VARIANT" >&2; exit 2 ;;
esac

rm -rf "$OUT"
python3 scripts/debug/2026-09-09/run-owned-emulator.py \
    --avd "Helix191_API${API}" \
    --port "$PORT" \
    --apk "app/build/outputs/apk/${VARIANT}/debug/app-${VARIANT}-debug.apk" \
    --test-apk "app/build/outputs/apk/androidTest/${VARIANT}/debug/app-${VARIANT}-debug-androidTest.apk" \
    --runner "com.helix.agent${SUFFIX}.test/com.helix.app.HelixAndroidJUnitRunner" \
    --classes com.helix.app.ui.SessionSearchDeviceTest \
    --recovery-setup-class com.helix.app.ui.SessionSearchDeviceTest \
    --instrument-arg recoveryPhase=verify \
    --after-script scripts/debug/2026-09-18/run-191-storage-connected.py \
    --output "$OUT"
