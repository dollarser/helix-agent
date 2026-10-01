#!/usr/bin/env bash
# Current source host-only verification. Never starts a device or consumes a real model account.
set -u
mkdir -p build
python3 scripts/with-host-slot.py -- ./gradlew test detekt spotlessCheck \
  :app:lintConsumerDebug :app:lintDeveloperDebug \
  :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest \
  --max-workers=2 --console=plain --continue --no-daemon --no-parallel > build/hxa236-host-verified.log 2>&1
result=$?
printf '%s\n' "$result" > build/hxa236-host-verified.exit
tail -n 55 build/hxa236-host-verified.log
exit "$result"
