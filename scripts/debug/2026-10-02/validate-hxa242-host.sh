#!/usr/bin/env bash
# HXA-242 host-only validation. No devices, accounts, commits or pushes.
set -euo pipefail
cd "$(dirname "$0")/../../.."
run_dir="build/hxa242/host-$(date +%Y%m%d-%H%M%S)-$$"
mkdir -p "$run_dir"
printf 'RUN_DIR=%s\n' "$run_dir"
export ORG_GRADLE_PROJECT_includeSpikes=true
python3 scripts/with-host-slot.py -- ./gradlew spotlessApply --console=plain --no-daemon --no-parallel --max-workers=2 > "$run_dir/format.log" 2>&1
python3 scripts/with-host-slot.py -- ./gradlew test detekt spotlessCheck lintDebug \
  :app:lintConsumerDebug :app:lintDeveloperDebug \
  :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest \
  --console=plain --no-daemon --no-parallel --max-workers=2 --continue > "$run_dir/host.log" 2>&1
./scripts/check-lockfiles.sh > "$run_dir/lockfiles.log" 2>&1
printf 'HXA-242 host build/test/static analysis and dependency lock checks passed.\n'
