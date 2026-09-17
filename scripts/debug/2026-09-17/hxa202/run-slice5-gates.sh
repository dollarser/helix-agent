#!/bin/bash
# HXA-202 slice 5: run the repo-canonical P1 and P3 gates verbatim from verification-matrix.md.
set -uo pipefail
ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")/../../../.." && pwd)
: "${JAVA_HOME:?Set JAVA_HOME to the JDK home}"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
cd "$ROOT_DIR"
mkdir -p build
L1=build/slice5-p1.log
./scripts/check-all.sh --source >"$L1" 2>&1; E1=$?
./gradlew spotlessCheck detekt >>"$L1" 2>&1; E2=$?
git diff --check >>"$L1" 2>&1; E3=$?
echo "P1_SOURCE_EXIT=$E1 P1_GRADLE_EXIT=$E2 P1_DIFFCHECK_EXIT=$E3" >>"$L1"
L2=build/slice5-p3.log
./gradlew :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest >"$L2" 2>&1; F1=$?
./gradlew :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest >>"$L2" 2>&1; F2=$?
./gradlew :app:lintConsumerDebug :app:lintDeveloperDebug >>"$L2" 2>&1; F3=$?
python3 scripts/debug/2026-09-09/run-owned-emulator.py --help >>"$L2" 2>&1; F4=$?
echo "P3_UNIT_EXIT=$F1 P3_ASSEMBLE_EXIT=$F2 P3_LINT_EXIT=$F3 P3_RUNNERHELP_EXIT=$F4" >>"$L2"
status=0
for code in "$E1" "$E2" "$E3" "$F1" "$F2" "$F3" "$F4"; do
    if [ "$code" -ne 0 ]; then
        status=1
    fi
done
exit "$status"
