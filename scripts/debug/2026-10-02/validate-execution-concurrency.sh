#!/usr/bin/env bash
set -euo pipefail
mkdir -p build/execution-concurrency
# Task-local host settings avoid concurrent lint initialization; no checks are disabled.
# These flags do not change application/Runtime concurrency or global Gradle configuration.
python3 scripts/with-host-slot.py -- ./gradlew test detekt spotlessCheck :app:lintConsumerDebug :app:lintDeveloperDebug :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest --console=plain --no-daemon --no-parallel --max-workers=2 --stacktrace > build/execution-concurrency/host.log 2>&1
