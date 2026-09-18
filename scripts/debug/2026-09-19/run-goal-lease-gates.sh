#!/usr/bin/env bash
# Shared host slot required. Each quadrant owns its emulator and leaves immutable APK evidence.
set -euo pipefail
cd "$(dirname "$0")/../../.."
prefix="${1:?new evidence prefix}"
./scripts/check-all.sh --all
./gradlew :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest
port=5654
for api in 29 36; do
  for flavor in consumer developer; do
    package="com.helix.agent"
    if [[ "$flavor" == developer ]]; then package="com.helix.agent.developer"; fi
    python3 scripts/debug/2026-09-09/run-owned-emulator.py \
      --avd "HelixApkUpgrade_API${api}_20260918" --port "$port" --memory-mb 4096 --cores 4 \
      --apk "app/build/outputs/apk/${flavor}/debug/app-${flavor}-debug.apk" \
      --test-apk "app/build/outputs/apk/androidTest/${flavor}/debug/app-${flavor}-debug-androidTest.apk" \
      --runner "${package}.test/com.helix.app.HelixAndroidJUnitRunner" \
      --classes com.helix.app.chat.GoalTimeLeaseDeviceTest,com.helix.app.chat.GoalUsageReservationsDeviceTest,com.helix.app.chat.GoalRunCoordinatorDeviceTest \
      --output "build/${prefix}-${flavor}-api${api}" --timeout 600
    port=$((port + 2))
  done
done
python3 scripts/debug/2026-09-19/summarize-goal-lease.py "$prefix"
