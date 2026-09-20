#!/usr/bin/env bash
# Run under the host slot. Reboot the owned emulator between actual process-death setup and verify.
set -euo pipefail
cd "$(dirname "$0")/../../.."
prefix="${1:?new evidence prefix}"
port="${2:-5676}"
case "${3:-all}" in
  all) ./scripts/check-all.sh --all; ./gradlew :app:assembleDeveloperDebugAndroidTest ;;
  --devices-only) ;; # Only after gates/build passed for these unchanged APKs.
  *) exit 2 ;;
esac
for api in 29 36; do
  python3 scripts/debug/2026-09-09/run-owned-emulator.py \
    --avd "HelixApkUpgrade_API${api}_20260918" --port "$port" --memory-mb 4096 --cores 4 \
    --apk app/build/outputs/apk/developer/debug/app-developer-debug.apk \
    --test-apk app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk \
    --runner com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner \
    --recovery-setup-class com.helix.app.proot.DetachedJobRebootDeviceTest --reboot-after-setup \
    --classes com.helix.app.proot.DetachedJobRebootDeviceTest \
    --instrument-arg recoveryPhase=verify \
    --output "build/${prefix}-api${api}" --timeout 600
  port=$((port + 2))
  python3 scripts/debug/2026-09-09/run-owned-emulator.py \
    --avd "HelixApkUpgrade_API${api}_20260918" --port "$port" --memory-mb 4096 --cores 4 \
    --apk app/build/outputs/apk/developer/debug/app-developer-debug.apk \
    --test-apk app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk \
    --runner com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner \
    --classes com.helix.app.proot.DetachedGoalJourneyDeviceTest,com.helix.app.proot.DetachedGoalRuntimeDeathDeviceTest \
    --output "build/${prefix}-control-api${api}" --timeout 600
  port=$((port + 2))
done
python3 scripts/debug/2026-09-18/summarize-job-submission.py "$prefix" 1
python3 scripts/debug/2026-09-18/summarize-job-submission.py "${prefix}-control" 3
