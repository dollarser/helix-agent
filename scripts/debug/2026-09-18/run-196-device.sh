#!/usr/bin/env bash
# Owned fresh emulator; API, port and new evidence directory are explicit.
set -euo pipefail
cd "$(dirname "$0")/../../.."
api="${1:?API}"
port="${2:?port}"
out="${3:?new evidence directory}"
python3 scripts/debug/2026-09-09/run-owned-emulator.py \
  --avd "HelixApkUpgrade_API${api}_20260918" --port "$port" --memory-mb 4096 --cores 4 \
  --apk app/build/outputs/apk/developer/debug/app-developer-debug.apk \
  --test-apk app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk \
  --runner com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner \
  --classes com.helix.app.proot.ProotDetachedOwnerDeathDeviceTest,com.helix.app.proot.ProotDetachedJobDeviceTest \
  --after-script scripts/debug/2026-09-18/verify-196-owner-death.py \
  --output "$out" --timeout 600
