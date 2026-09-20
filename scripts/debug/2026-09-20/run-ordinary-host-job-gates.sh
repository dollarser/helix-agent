#!/usr/bin/env bash
# Run with the shared host slot; each run owns its emulator and synthetic data.
set -euo pipefail
cd "$(dirname "$0")/../../.."
prefix="${1:?new evidence prefix}"
port="${2:-5678}"
case "${3:-all}" in
  all) ./scripts/check-all.sh --all; ./gradlew :app:assembleDeveloperDebugAndroidTest ;;
  --devices-only) ;; # Requires host gates and unchanged APKs.
  *) exit 2 ;;
esac
for api in 29 36; do
  python3 scripts/debug/2026-09-09/run-owned-emulator.py \
    --avd "HelixApkUpgrade_API${api}_20260918" --port "$port" --memory-mb 4096 --cores 4 \
    --apk app/build/outputs/apk/developer/debug/app-developer-debug.apk \
    --test-apk app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk \
    --runner com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner \
    --classes com.helix.app.proot.DetachedJobHostJourneyDeviceTest \
    --instrument-arg hostJobPhase=prepare \
    --after-script scripts/debug/2026-09-20/run-ordinary-host-job.py \
    --output "build/${prefix}-api${api}" --timeout 600
  port=$((port + 2))
done
python3 scripts/debug/2026-09-18/summarize-job-submission.py "$prefix" 1
python3 scripts/debug/2026-09-20/summarize-ordinary-host-job.py "$prefix"
