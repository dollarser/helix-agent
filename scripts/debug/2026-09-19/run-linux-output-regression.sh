#!/usr/bin/env bash
# Run through with-host-slot.py after the immutable developer APK build.
set -euo pipefail
cd "$(dirname "$0")/../../.."
prefix="${1:?new evidence prefix}"
port=5662
for api in 29 36; do
  python3 scripts/debug/2026-09-09/run-owned-emulator.py \
    --avd "HelixApkUpgrade_API${api}_20260918" --port "$port" --memory-mb 4096 --cores 4 \
    --apk app/build/outputs/apk/developer/debug/app-developer-debug.apk \
    --test-apk app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk \
    --runner com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner \
    --classes com.helix.app.proot.LinuxRunToolE2eDeviceTest \
    --output "build/${prefix}-api${api}" --timeout 600
  port=$((port + 2))
done
python3 scripts/debug/2026-09-18/summarize-job-submission.py "$prefix" 7
