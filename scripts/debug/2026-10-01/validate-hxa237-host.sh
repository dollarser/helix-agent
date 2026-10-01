#!/usr/bin/env bash
# Local host only: never launches a device or consumes a real model account.
set -u
mkdir -p build/hxa237
python3 scripts/with-host-slot.py -- ./gradlew test detekt spotlessCheck \
  :app:lintConsumerDebug :app:lintDeveloperDebug \
  :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest \
  --max-workers=2 --console=plain --continue --no-daemon --no-parallel > build/hxa237/host.log 2>&1
result=$?
printf '%s\n' "$result" > build/hxa237/host.exit
tail -n 35 build/hxa237/host.log
exit "$result"
