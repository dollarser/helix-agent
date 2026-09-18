#!/usr/bin/env bash
# Invoke through with-host-slot.py after building both app and instrumentation APKs.
set -euo pipefail
cd "$(dirname "$0")/../../.."
prefix="${1:?new evidence prefix}"
port=5674
for api in 29 36; do
  for flavor in consumer developer; do
    package="com.helix.agent"
    if [[ "$flavor" == developer ]]; then package="com.helix.agent.developer"; fi
    python3 scripts/debug/2026-09-09/run-owned-emulator.py \
      --avd "HelixApkUpgrade_API${api}_20260918" --port "$port" --memory-mb 4096 --cores 4 \
      --apk "app/build/outputs/apk/${flavor}/debug/app-${flavor}-debug.apk" \
      --test-apk "app/build/outputs/apk/androidTest/${flavor}/debug/app-${flavor}-debug-androidTest.apk" \
      --runner "${package}.test/com.helix.app.HelixAndroidJUnitRunner" \
      --classes com.helix.app.proot.ExecutionOwnershipDeviceTest,com.helix.app.plan.PlanSubmitIntegrationDeviceTest \
      --output "build/${prefix}-${flavor}-api${api}" --timeout 600
    port=$((port + 2))
  done
done
