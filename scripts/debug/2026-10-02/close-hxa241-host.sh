#!/usr/bin/env bash
# Host-only validation of HXA-241; no device, account, commit or push operations.
set -euo pipefail
cd "$(dirname "$0")/../../.."
mkdir -p build/hxa241
python3 scripts/with-host-slot.py -- ./gradlew test detekt spotlessCheck \
  :app:lintConsumerDebug :app:lintDeveloperDebug \
  :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest \
  --console=plain --no-daemon --no-parallel --max-workers=2 > build/hxa241/host-final.log 2>&1
python3 -O -m unittest discover -s scripts/tests -p test_device_baseline_runner.py > build/hxa241/runner-final.log 2>&1
python3 -O -m unittest discover -s scripts/tests -p test_instrumentation_junit.py > build/hxa241/instrumentation-final.log 2>&1
./scripts/check-all.sh --source > build/hxa241/source-final.log 2>&1
./scripts/check-all.sh --artifacts > build/hxa241/artifacts-final.log 2>&1
git diff --check
printf 'HXA-241 final host, source, runner and APK gates passed. Device acceptance is recorded separately.\n'
