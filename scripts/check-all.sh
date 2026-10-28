#!/usr/bin/env bash
# Shared local/CI gates. Device tests and network/runtime-asset qualification are explicit separate runs.
set -euo pipefail
readonly project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"

# Historical experiments are opt-in with -PincludeSpikes=true, not product gates.
gradle_profile=()
if [[ "${HELIX_GRADLE_PROFILE:-0}" == 1 ]]; then
    gradle_profile+=(--profile)
fi

source_checks() {
    python3 scripts/run-host-tests.py
    ./scripts/check-docs.sh
    ./scripts/verify-adr.sh
    ./scripts/check-i18n.sh
    ./scripts/check-secrets.sh
}

analysis_checks() {
    ./gradlew ${gradle_profile[@]+"${gradle_profile[@]}"} spotlessCheck detekt lintDebug lintRelease lintConsumerDebug lintDeveloperDebug lintConsumerRelease lintDeveloperRelease
}

test_build_checks() {
    ./gradlew ${gradle_profile[@]+"${gradle_profile[@]}"} test :app:assembleConsumerDebug :app:assembleDeveloperDebug :runtime:proot-app:assembleDebug :runtime:cli-app:assembleDebug \
        compileDebugAndroidTestSources :app:compileConsumerDebugAndroidTestSources :app:compileDeveloperDebugAndroidTestSources \
        :app:assembleConsumerRelease :app:assembleDeveloperRelease :runtime:proot-app:assembleRelease :runtime:cli-app:assembleRelease
    ./gradlew ${gradle_profile[@]+"${gradle_profile[@]}"} -PincludeReleaseSmoke=true \
        :tests:release-smoke:assembleDebugAndroidTest :tests:release-smoke:lintDebug
}

debug_analysis_checks() {
    ./gradlew ${gradle_profile[@]+"${gradle_profile[@]}"} spotlessCheck detekt lintDebug lintConsumerDebug lintDeveloperDebug
}

release_analysis_checks() {
    ./gradlew ${gradle_profile[@]+"${gradle_profile[@]}"} lintRelease lintConsumerRelease lintDeveloperRelease
}

debug_test_build_checks() {
    # Test source correctness is compiled; Android Lint is reserved for production.
    ./gradlew ${gradle_profile[@]+"${gradle_profile[@]}"} debugUnitTests \
        :app:assembleConsumerDebug :app:assembleDeveloperDebug \
        compileDebugAndroidTestSources :app:compileConsumerDebugAndroidTestSources :app:compileDeveloperDebugAndroidTestSources
}

build_checks() {
    ./scripts/check-lockfiles.sh
    analysis_checks
    test_build_checks
}

artifact_checks() {
    ./scripts/verify-variant-boundaries.sh
    ./scripts/check-cli-runtime-boundary.sh --skip-integrated-apk-scan
}

release_artifact_checks() {
    python3 scripts/verify-integrated-runtime-apks.py --build-type release
}

case "${1:---debug}" in
    --debug) source_checks; ./scripts/check-lockfiles.sh; debug_analysis_checks; debug_test_build_checks; artifact_checks ;;
    --source) source_checks ;;
    --analysis) analysis_checks ;;
    --release-analysis) release_analysis_checks ;;
    --debug-analysis) debug_analysis_checks ;;
    --debug-tests-build) ./scripts/check-lockfiles.sh; debug_test_build_checks ;;
    --tests-build) ./scripts/check-lockfiles.sh; test_build_checks ;;
    --build) build_checks ;;
    --artifacts) artifact_checks ;;
    --release-artifacts) release_artifact_checks ;;
    --all) source_checks; build_checks; artifact_checks; release_artifact_checks ;;
    *) printf 'Usage: %s [--debug (default)|--source|--analysis|--release-analysis|--tests-build|--debug-analysis|--debug-tests-build|--build|--artifacts|--release-artifacts|--all]\n' "$0" >&2; exit 2 ;;
esac
