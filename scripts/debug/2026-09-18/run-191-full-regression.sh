#!/usr/bin/env bash
# HXA-191 full post-change regression, one re-runnable entry point:
#   1. FOUR-quadrant NAMED theme device matrix (consumer|developer x API29|API36) on the
#      HelixApkUpgrade_API* AVDs — the real system night mode + font scale, running
#      com.helix.app.ui.HelixThemeDeviceTest twice per mode (setup seeds+kills; verify is a
#      fresh process). Foreground MainActivity appearance captured per mode.
#   2. FOUR-quadrant ALREADY-MERGED search regression via run-191-search-device.sh on the
#      Helix191_API* AVDs — SessionSearchDeviceTest (app UI, recovery verify) plus the storage
#      after-script that runs SessionSearchQueryDeviceTest (Room query, expects 4/4).
# Every heavy Gradle/owned-emulator run is serialized through the shared build/device host slot
# (never two heavy runs at once). A failing quadrant is recorded and the run continues so the
# full evidence set is produced; the exit code is non-zero if any quadrant failed.
#
# Usage: run-191-full-regression.sh     (no args; runs theme matrix then search regression)
set -uo pipefail
readonly repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$repo_root"

overall=0

echo "################ THEME MATRIX (4 quadrants) ################"
if bash scripts/debug/2026-09-18/run-191-theme-matrix.sh; then
  echo "THEME MATRIX: all quadrants PASS"
else
  echo "THEME MATRIX: at least one quadrant FAIL"
  overall=1
fi

echo "################ SEARCH REGRESSION (4 quadrants) ################"
for q in "consumer 36 5642" "consumer 29 5644" "developer 36 5646" "developer 29 5648"; do
  # shellcheck disable=SC2086
  set -- $q
  echo "=== search quadrant: $1 API$2 (port $3) ==="
  if python3 scripts/debug/2026-09-18/with-host-slot.py -- \
      bash scripts/debug/2026-09-18/run-191-search-device.sh "$1" "$2" "$3" "build/hxa-191-search-${1}-api${2}"; then
    echo "SEARCH PASS: $1 API$2"
  else
    echo "SEARCH FAIL: $1 API$2"
    overall=1
  fi
done

echo "################ FULL REGRESSION DONE (overall=$overall) ################"
exit "$overall"
