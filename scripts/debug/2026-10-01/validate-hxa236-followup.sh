#!/usr/bin/env bash
# Host-only: no devices, real accounts, cleanup, commit or publish.
set -u
mkdir -p build/hxa236-followup
python3 scripts/with-host-slot.py -- ./gradlew test detekt spotlessCheck \
  :app:lintConsumerDebug :app:lintDeveloperDebug \
  :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest \
  --max-workers=2 --console=plain --continue --no-daemon --no-parallel > build/hxa236-followup/host.log 2>&1
result=$?
printf '%s\n' "$result" > build/hxa236-followup/host.exit
tail -n 30 build/hxa236-followup/host.log
exit "$result"
