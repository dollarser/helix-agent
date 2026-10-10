#!/usr/bin/env bash
# Run through with-host-slot.py; never borrow an existing emulator.
set -euo pipefail
cd "$(dirname "$0")/../../.."
prefix="${1:?new evidence prefix}"
port="${2:-5658}"
./scripts/check-all.sh --all
./gradlew :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest
for api in 29 36; do
  for flavor in consumer developer; do
    package="com.helix.agent"
    classes="com.helix.app.CommandExecutionDetailsDeviceTest"
    if [[ "$flavor" == developer ]]; then
      package="com.helix.agent.developer"
      classes="$classes,com.helix.app.proot.CommandResultBrowseDeviceTest,com.helix.app.proot.DetachedJobDashboardDeviceTest"
    fi
    python3 scripts/run-owned-emulator.py --raw-results \
      --avd "HelixApkUpgrade_API${api}_20260918" --port "$port" --memory-mb 4096 --cores 4 \
      --apk "app/build/outputs/apk/${flavor}/debug/app-${flavor}-debug.apk" \
      --test-apk "app/build/outputs/apk/androidTest/${flavor}/debug/app-${flavor}-debug-androidTest.apk" \
      --runner "${package}.test/com.helix.app.HelixAndroidJUnitRunner" \
      --classes "$classes" --output "build/${prefix}-${flavor}-api${api}" --timeout 600
    port=$((port + 2))
  done
done
for flavor in consumer developer; do
  python3 scripts/summarize-owned-runs.py "build/${prefix}-${flavor}-api29" "build/${prefix}-${flavor}-api36" --output "build/${prefix}-${flavor}-summary.json"
done
