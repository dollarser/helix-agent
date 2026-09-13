#!/usr/bin/env bash
# Shared local/CI gates. Device tests and network/runtime-asset qualification are explicit separate runs.
set -euo pipefail
readonly project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"

source_checks() {
    python3 scripts/test-review-gates.py
    ./scripts/check-docs.sh
    ./scripts/verify-adr.sh
    ./scripts/check-i18n.sh
    ./scripts/check-secrets.sh
}

build_checks() {
    ./gradlew spotlessCheck detekt test lintDebug lintRelease lintConsumerDebug lintDeveloperDebug lintConsumerRelease lintDeveloperRelease
    ./gradlew :app:assembleConsumerDebug :app:assembleDeveloperDebug :runtime:proot-app:assembleDebug :runtime:cli-app:assembleDebug \
        :app:assembleConsumerRelease :app:assembleDeveloperRelease :runtime:proot-app:assembleRelease :runtime:cli-app:assembleRelease
    ./scripts/check-lockfiles.sh
}

artifact_checks() {
    ./scripts/verify-variant-boundaries.sh
    ./scripts/check-cli-runtime-boundary.sh
}

case "${1:---all}" in
    --source) source_checks ;;
    --build) build_checks ;;
    --artifacts) artifact_checks ;;
    --all) source_checks; build_checks; artifact_checks ;;
    *) printf 'Usage: %s [--source|--build|--artifacts|--all]\n' "$0" >&2; exit 2 ;;
esac
