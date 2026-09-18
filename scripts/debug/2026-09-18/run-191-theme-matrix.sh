#!/usr/bin/env bash
# HXA-191 dark-theme: the FULL four-quadrant NAMED-theme device matrix. Each quadrant runs on
# its OWN exclusive AVD (HelixApkUpgrade_API*) and a reserved even console port, serialized
# through the shared build/device host slot (never two heavy runs at once). Per-quadrant output
# (screenshots-free; uimode/font/both-mode instrument logs + the real LIGHT_STATUS_BARS
# appearance flag + APK hashes + closed.json) lands under build/ (ignored).
#
# Each quadrant = one emulator launch that drives the REAL system night mode + font scale and
# runs com.helix.app.ui.HelixThemeDeviceTest twice per mode (recoveryPhase=setup seeds the
# process identity and kills; recoveryPhase=verify asserts a fresh process re-derives the theme):
#   light (night no, font 1.0)  -> setup + verify
#   dark  (night yes, font 1.3) -> setup + verify   (the dark pass doubles as the large-font run)
#
# Usage: run-191-theme-matrix.sh [variant [api [port]]]   (no args = all four quadrants)
#   e.g. run-191-theme-matrix.sh consumer 36 5700
set -uo pipefail
readonly repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$repo_root"

run_quadrant() {
  local variant="$1" api="$2" port="$3"
  local out="build/hxa-191-theme-${variant}-api${api}"
  echo "=== theme quadrant: ${variant} API${api} (port ${port}) -> ${out} ==="
  python3 scripts/debug/2026-09-18/with-host-slot.py -- \
    python3 scripts/debug/2026-09-18/run-191-theme-device.py "$variant" "$api" "$port" "$out"
}

if [ "$#" -ge 3 ]; then
  run_quadrant "$1" "$2" "$3"
  exit $?
fi

overall=0
for q in "consumer 36 5700" "consumer 29 5702" "developer 36 5704" "developer 29 5706"; do
  # shellcheck disable=SC2086
  set -- $q
  if run_quadrant "$1" "$2" "$3"; then
    echo "PASS: $1 API$2"
  else
    echo "FAIL: $1 API$2"
    overall=1
  fi
done
exit "$overall"
