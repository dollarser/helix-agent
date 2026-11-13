#!/usr/bin/env bash
# Shared local/CI gates. Device tests and network/runtime-asset qualification are explicit separate runs.
set -euo pipefail
readonly project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"

# Historical experiments are opt-in with -PincludeSpikes=true, not product gates.
gradle_options=()
if [[ "${HELIX_GRADLE_PROFILE:-0}" == 1 ]]; then
    gradle_options+=(--profile)
fi
if [[ "${HELIX_CI_GRADLE_BUDGET:-0}" == 1 ]]; then
    # Public ubuntu-24.04 has 16 GiB RAM. Kotlin gets its own heap alongside
    # Gradle's 4 GiB; bound concurrency without changing any gate tasks.
    gradle_options+=(--max-workers=2 --no-parallel -Pkotlin.daemon.jvmargs=-Xmx6g)
fi

source_checks() {
    python3 scripts/run-host-tests.py
    ./scripts/check-docs.sh
    ./scripts/verify-adr.sh
    ./scripts/check-i18n.sh
    ./scripts/check-secrets.sh
    python3 scripts/verify-preview-assets.py
    python3 scripts/prepare-vad-assets.py --verify-lock
}

analysis_checks() {
    ./gradlew ${gradle_options[@]+"${gradle_options[@]}"} spotlessCheck detekt lintDebug lintRelease lintConsumerDebug lintDeveloperDebug lintConsumerRelease lintDeveloperRelease
}

test_build_checks() {
    ./gradlew ${gradle_options[@]+"${gradle_options[@]}"} test :app:assembleConsumerDebug :app:assembleDeveloperDebug :runtime:proot-app:assembleDebug :runtime:cli-app:assembleDebug \
        compileDebugAndroidTestSources :app:compileConsumerDebugAndroidTestSources :app:compileDeveloperDebugAndroidTestSources \
        :app:assembleConsumerRelease :app:assembleDeveloperRelease :runtime:proot-app:assembleRelease :runtime:cli-app:assembleRelease
    ./gradlew ${gradle_options[@]+"${gradle_options[@]}"} -PincludeReleaseSmoke=true \
        :tests:release-smoke:assembleDebugAndroidTest :tests:release-smoke:lintDebug
}

debug_analysis_tasks=(spotlessCheck detekt lintDebug lintConsumerDebug lintDeveloperDebug)
debug_build_tasks=(
    debugUnitTests :app:assembleConsumerDebug :app:assembleDeveloperDebug
    compileDebugAndroidTestSources :app:compileConsumerDebugAndroidTestSources :app:compileDeveloperDebugAndroidTestSources
)

debug_analysis_checks() {
    ./gradlew ${gradle_options[@]+"${gradle_options[@]}"} "${debug_analysis_tasks[@]}"
}

# One task graph shares configuration and input snapshots across build and analysis.
debug_build_analysis_checks() {
    ./scripts/check-lockfiles.sh
    ./gradlew ${gradle_options[@]+"${gradle_options[@]}"} "${debug_build_tasks[@]}" "${debug_analysis_tasks[@]}"
}

release_analysis_checks() {
    ./gradlew ${gradle_options[@]+"${gradle_options[@]}"} lintRelease lintConsumerRelease lintDeveloperRelease
}

debug_test_build_checks() {
    # Test source correctness is compiled; Android Lint is reserved for production.
    ./gradlew ${gradle_options[@]+"${gradle_options[@]}"} "${debug_build_tasks[@]}"
}

build_checks() {
    ./scripts/check-lockfiles.sh
    analysis_checks
    test_build_checks
}

artifact_checks() {
    python3 scripts/prepare-vad-assets.py --apk app/build/outputs/apk/consumer/debug/app-consumer-debug.apk
    python3 scripts/prepare-vad-assets.py --apk app/build/outputs/apk/developer/debug/app-developer-debug.apk
    ./scripts/verify-variant-boundaries.sh
    ./scripts/check-cli-runtime-boundary.sh --skip-integrated-apk-scan
}

release_artifact_checks() {
    ./scripts/verify-variant-boundaries.sh --helper-release-boundary
    python3 scripts/prepare-vad-assets.py --apk app/build/outputs/apk/consumer/release/app-consumer-release-unsigned.apk
    python3 scripts/prepare-vad-assets.py --apk app/build/outputs/apk/developer/release/app-developer-release-unsigned.apk
    python3 scripts/verify-integrated-runtime-apks.py --build-type release
}

case "${1:---debug}" in
    --debug) source_checks; debug_build_analysis_checks; artifact_checks ;;
    --source) source_checks ;;
    --analysis) analysis_checks ;;
    --release-analysis) release_analysis_checks ;;
    --debug-build-analysis) debug_build_analysis_checks ;;
    --debug-analysis) debug_analysis_checks ;;
    --debug-tests-build) ./scripts/check-lockfiles.sh; debug_test_build_checks ;;
    --tests-build) ./scripts/check-lockfiles.sh; test_build_checks ;;
    --build) build_checks ;;
    --artifacts) artifact_checks ;;
    --release-artifacts) release_artifact_checks ;;
    --all) source_checks; build_checks; artifact_checks; release_artifact_checks ;;
    *) printf 'Usage: %s [--debug (default)|--source|--analysis|--release-analysis|--tests-build|--debug-build-analysis|--debug-analysis|--debug-tests-build|--build|--artifacts|--release-artifacts|--all]\n' "$0" >&2; exit 2 ;;
esac
