#!/usr/bin/env bash
# Preserve each gate's failure and timing, including when Gradle fails before producing reports.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
case "${1:-}" in
    --source|--analysis|--tests-build|--debug-analysis|--debug-tests-build|--artifacts|--release-artifacts) gate="${1#--}" ;;
    *) echo "Usage: $0 --source|--analysis|--tests-build|--debug-analysis|--debug-tests-build|--artifacts|--release-artifacts" >&2; exit 2 ;;
esac
mkdir -p build/ci
started=$SECONDS
set +e
./scripts/check-all.sh "$1" 2>&1 | tee "build/ci/$gate.log"
result=$?
set -e
elapsed=$((SECONDS - started))
printf '%s\t%s\t%s\n' "$gate" "$elapsed" "$result" > "build/ci/$gate.tsv"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    printf '\n### %s\n\nDuration: %s seconds. Exit code: %s.\n' \
        "$gate" "$elapsed" "$result" >> "$GITHUB_STEP_SUMMARY"
fi
exit "$result"
