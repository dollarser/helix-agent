#!/usr/bin/env bash
# Production Goal/Dispatcher Job, explicit Runtime death; fresh owned install per API.
set -euo pipefail
cd "$(dirname "$0")/../../.."
prefix="${1:?new evidence prefix}"
port="${2:-5678}"
./scripts/check-all.sh --all
./gradlew :app:assembleDeveloperDebugAndroidTest
for api in 29 36; do
  python3 scripts/run-owned-emulator.py --raw-results \
    --avd "HelixApkUpgrade_API${api}_20260918" --port "$port" --memory-mb 4096 --cores 4 \
    --apk app/build/outputs/apk/developer/debug/app-developer-debug.apk \
    --test-apk app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk \
    --runner com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner \
    --classes com.helix.app.proot.DetachedGoalJourneyDeviceTest,com.helix.app.proot.DetachedGoalRuntimeDeathDeviceTest \
    --output "build/${prefix}-api${api}" --timeout 600
  port=$((port + 2))
done
python3 scripts/summarize-owned-runs.py "build/${prefix}-api29" "build/${prefix}-api36" --output "build/${prefix}-summary.json"
