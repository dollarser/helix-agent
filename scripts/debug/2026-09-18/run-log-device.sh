#!/usr/bin/env bash
# Dedicated emulator process, fresh evidence, teardown owned by the common runner.
set -euo pipefail
api="${1:?API level}"
output="${2:?new evidence directory}"
port=5670
if [[ "$api" == 36 ]]; then port=5672; fi
python3 scripts/debug/2026-09-09/run-owned-emulator.py \
  --avd "HelixApkUpgrade_API${api}_20260918" --port "$port" \
  --memory-mb 4096 --cores 4 --output "$output" \
  --apk app/build/outputs/apk/developer/debug/app-developer-debug.apk \
  --test-apk app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk \
  --runner com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner \
  --classes com.helix.app.proot.ProotLogStreamDeviceTest --timeout 600
