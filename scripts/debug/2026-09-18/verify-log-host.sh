#!/usr/bin/env bash
# Exact G2 targets for HXA-195, plus the integrated APK exclusion gate.
set -euo pipefail
./gradlew :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest
./gradlew :runtime:proot-core:test :runtime:proot-ipc:testDebugUnitTest \
  :runtime:proot-client:testDebugUnitTest :runtime:proot-app:testDebugUnitTest
./gradlew :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest
./gradlew :app:lintConsumerDebug :app:lintDeveloperDebug :runtime:proot-app:lintDebug :runtime:proot-client:lintDebug
python3 scripts/verify-integrated-runtime-apks.py
python3 scripts/verify-integrated-runtime-apks.py --build-type release
