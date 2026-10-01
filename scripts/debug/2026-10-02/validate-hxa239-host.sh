#!/usr/bin/env bash
set -euo pipefail
mkdir -p build/hxa239
python3 scripts/with-host-slot.py -- ./gradlew test detekt spotlessCheck :app:lintConsumerDebug :app:lintDeveloperDebug :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest --console=plain --no-daemon --no-parallel --max-workers=2 --stacktrace > build/hxa239/host.log 2>&1
