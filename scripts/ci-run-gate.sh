#!/usr/bin/env bash
# Preserve each gate's failure and timing, including when Gradle fails before producing reports.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
case "${1:-}" in
    --source|--build|--analysis|--release-analysis|--tests-build|--debug-build-analysis|--debug-analysis|--debug-tests-build|--artifacts|--release-artifacts) gate="${1#--}" ;;
    *) echo "Usage: $0 --source|--build|--analysis|--release-analysis|--tests-build|--debug-build-analysis|--debug-analysis|--debug-tests-build|--artifacts|--release-artifacts" >&2; exit 2 ;;
esac
mkdir -p build/ci
export HELIX_GRADLE_PROFILE=1
export HELIX_CI_GRADLE_BUDGET=1
exec python3 scripts/ci/run_gate.py "$gate"
