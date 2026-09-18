#!/usr/bin/env bash
# Run through with-host-slot.py; each API gets a freshly owned emulator and distinct port.
set -euo pipefail
cd "$(dirname "$0")/../../.."
prefix="${1:?new evidence prefix}"
port="${2:-5674}"
for api in 29 36; do
  python3 scripts/debug/2026-09-09/run-owned-emulator.py \
    --avd "HelixApkUpgrade_API${api}_20260918" --port "$port" --memory-mb 4096 --cores 4 \
    --apk app/build/outputs/apk/developer/debug/app-developer-debug.apk \
    --test-apk app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk \
    --runner com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner \
    --classes com.helix.app.proot.ProotJobE2eDeviceTest,com.helix.app.proot.ProotDetachedJobDeviceTest,com.helix.app.proot.DetachedJobControlDeviceTest,com.helix.app.proot.DetachedJobLaunchDeviceTest,com.helix.app.proot.DetachedJobCollectionDeviceTest,com.helix.app.proot.DetachedJobRegistrationDeviceTest \
    --output "build/${prefix}-api${api}" --timeout 600
  port=$((port + 2))
done
