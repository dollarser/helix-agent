#!/usr/bin/env bash
# Current-source host verification only. Does not start a device or use a real account.
set -u
python3 scripts/with-host-slot.py -- ./gradlew test detekt spotlessCheck \
  :app:lintConsumerDebug :app:lintDeveloperDebug \
  :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest \
  --max-workers=2 --console=plain --continue > build/hxa235-host-verified.log 2>&1
result=$?
printf '%s\n' "$result" > build/hxa235-host-verified.exit
tail -n 45 build/hxa235-host-verified.log
exit "$result"
