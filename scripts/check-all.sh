#!/usr/bin/env bash
# Shared local/CI gates. Device tests and network/runtime-asset qualification are explicit separate runs.
set -euo pipefail
readonly project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"

source_checks() {
    python3 scripts/test-review-gates.py
    python3 -m unittest discover -s scripts/tests -p 'test_ci_*.py'
    python3 -m unittest discover -s scripts/tests -p test_staged_gate.py
    python3 -m unittest discover -s scripts/tests -p test_session_export_validator.py
    python3 -m unittest discover -s scripts/tests -p test_terminal_reports.py
    python3 -m unittest discover -s scripts/tests -p test_product_journeys.py
    python3 -m unittest discover -s scripts/tests -p test_owned_acceptance.py
    ./scripts/check-docs.sh
    ./scripts/verify-adr.sh
    ./scripts/check-i18n.sh
    ./scripts/check-secrets.sh
}

analysis_checks() {
    ./gradlew spotlessCheck detekt lintDebug lintRelease lintConsumerDebug lintDeveloperDebug lintConsumerRelease lintDeveloperRelease
}

test_build_checks() {
    ./gradlew test :app:assembleConsumerDebug :app:assembleDeveloperDebug :runtime:proot-app:assembleDebug :runtime:cli-app:assembleDebug \
        :app:assembleConsumerRelease :app:assembleDeveloperRelease :runtime:proot-app:assembleRelease :runtime:cli-app:assembleRelease
    ./scripts/check-lockfiles.sh
}

debug_analysis_checks() {
    ./gradlew spotlessCheck detekt lintDebug lintConsumerDebug lintDeveloperDebug
}

debug_test_build_checks() {
    # Retain every existing test; only release lint/packaging is omitted.
    ./gradlew test \
        :app:assembleConsumerDebug :app:assembleDeveloperDebug
    ./scripts/check-lockfiles.sh
}

build_checks() {
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

case "${1:---all}" in
    --source) source_checks ;;
    --analysis) analysis_checks ;;
    --debug-analysis) debug_analysis_checks ;;
    --debug-tests-build) debug_test_build_checks ;;
    --tests-build) test_build_checks ;;
    --build) build_checks ;;
    --artifacts) artifact_checks ;;
    --release-artifacts) release_artifact_checks ;;
    --all) source_checks; build_checks; artifact_checks; release_artifact_checks ;;
    *) printf 'Usage: %s [--source|--analysis|--tests-build|--debug-analysis|--debug-tests-build|--build|--artifacts|--release-artifacts|--all]\n' "$0" >&2; exit 2 ;;
esac
