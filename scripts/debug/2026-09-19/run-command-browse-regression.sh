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
      classes="$classes,com.helix.app.proot.CommandResultBrowseDeviceTest"
    fi
    python3 scripts/debug/2026-09-09/run-owned-emulator.py \
      --avd "HelixApkUpgrade_API${api}_20260918" --port "$port" --memory-mb 4096 --cores 4 \
      --apk "app/build/outputs/apk/${flavor}/debug/app-${flavor}-debug.apk" \
      --test-apk "app/build/outputs/apk/androidTest/${flavor}/debug/app-${flavor}-debug-androidTest.apk" \
      --runner "${package}.test/com.helix.app.HelixAndroidJUnitRunner" \
      --classes "$classes" --output "build/${prefix}-${flavor}-api${api}" --timeout 600
    port=$((port + 2))
  done
done
python3 scripts/debug/2026-09-19/summarize-command-browse.py "$prefix"
