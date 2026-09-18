#!/usr/bin/env bash
# Run through with-host-slot.py. Each command owns a fresh emulator and preserves its evidence.
set -euo pipefail
cd "$(dirname "$0")/../../.."
prefix="${1:?new evidence prefix}"
for api in 29 36; do
  python3 scripts/verify-integrated-runtimes.py \
    --avd "HelixApkUpgrade_API${api}_20260918" --port 5670 --memory-mb 4096 --cores 4 \
    --output "build/${prefix}-runtime-api${api}"
  python3 scripts/debug/2026-09-09/run-owned-emulator.py \
    --avd "HelixApkUpgrade_API${api}_20260918" --port 5672 --memory-mb 4096 --cores 4 \
    --apk app/build/outputs/apk/developer/debug/app-developer-debug.apk \
    --test-apk app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk \
    --runner com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner \
    --classes com.helix.app.provider.CliResultFetchDeviceTest,com.helix.app.proot.ProotDetachedOwnerDeathDeviceTest,com.helix.app.proot.ProotDetachedJobDeviceTest \
    --after-script scripts/debug/2026-09-18/verify-196-owner-death.py \
    --output "build/${prefix}-stream-lease-api${api}" --timeout 600
done
