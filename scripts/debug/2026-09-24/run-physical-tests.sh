#!/bin/bash
# 2026-09-24 Physical Device Acceptance Runner
# Targeted for connected physical device: OnePlus 6T (ONEPLUS A6013, API 34, arm64-v8a)
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
SERIAL="${1:-561e3b15}"
OUT="$REPO/build/real-device-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"
cd "$REPO"

export ANDROID_SERIAL="$SERIAL"

echo "=== Physical Device Test Runner ==="
echo "Serial: $SERIAL"
echo "Output: $OUT"

# 1. Device Facts
{
  echo "run_utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "serial: $SERIAL"
  for p in ro.product.model ro.product.manufacturer ro.build.version.sdk ro.build.version.release ro.build.version.incremental ro.product.cpu.abi ro.build.type; do
    echo "$p: $(adb -s "$SERIAL" shell getprop "$p" | tr -d '\r')"
  done
  echo "pagesize: $(adb -s "$SERIAL" shell getconf PAGESIZE | tr -d '\r')"
  echo "git_head: $(git rev-parse HEAD)"
  echo "git_dirty_paths: $(git status --porcelain | wc -l | tr -d ' ')"
} | tee "$OUT/device-facts.txt"

# Ensure screen is on and unlocked
adb -s "$SERIAL" shell svc power stayon true || true
adb -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP || true

# Function to restore device state on exit
cleanup() {
  echo "Restoring device state..."
  adb -s "$SERIAL" shell svc power stayon false || true
}
trap cleanup EXIT

# 2. Run core:storage connected tests (Room migration chain & ContentStore)
echo "=== Step 1: Running :core:storage:connectedDebugAndroidTest ==="
./gradlew :core:storage:connectedDebugAndroidTest --console=plain > "$OUT/storage-device.log" 2>&1
sto_rc=$?
echo "core:storage exit code: $sto_rc"

cp -R core/storage/build/reports/androidTests/connected "$OUT/storage-reports" 2>/dev/null || true

# Summary
echo "storage_rc=$sto_rc" | tee "$OUT/summary-step1.txt"

exit $sto_rc
