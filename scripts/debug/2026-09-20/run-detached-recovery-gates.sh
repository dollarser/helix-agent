#!/usr/bin/env bash
# New owned emulator per API; setup kills its own main process, verify checks the new PID.
set -euo pipefail
cd "$(dirname "$0")/../../.."
prefix="${1:?new evidence prefix}"
port="${2:-5678}"
phase="${3:-setup}"
followup=()
if [[ "$phase" == setup-running ]]; then
  followup=(--between-recovery-script scripts/debug/2026-09-20/observe-live-job-after-host-death.py)
fi
case "${4:-all}" in
  all) ./scripts/check-all.sh --all; ./gradlew :app:assembleDeveloperDebugAndroidTest ;;
  --devices-only) ;; # Requires a successful host gate and unchanged APKs.
  *) exit 2 ;;
esac
for api in 29 36; do
  python3 scripts/debug/2026-09-09/run-owned-emulator.py \
    --avd "HelixApkUpgrade_API${api}_20260918" --port "$port" --memory-mb 4096 --cores 4 \
    --apk app/build/outputs/apk/developer/debug/app-developer-debug.apk \
    --test-apk app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk \
    --runner com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner \
    --recovery-setup-class com.helix.app.proot.DetachedGoalRecoveryDeviceTest \
    --recovery-setup-phase "$phase" \
    "${followup[@]}" \
    --classes com.helix.app.proot.DetachedGoalRecoveryDeviceTest \
    --instrument-arg recoveryPhase=verify \
    --output "build/${prefix}-api${api}" --timeout 600
  port=$((port + 2))
done
python3 scripts/debug/2026-09-18/summarize-job-submission.py "$prefix" 1
