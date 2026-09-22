#!/usr/bin/env bash
# Run inside the shared host slot; caller configures JDK/SDK and bounded Gradle settings.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$root"
output="${1:?Provide a fresh evidence directory}"
mkdir "$output"
git rev-parse HEAD > "$output/source-head.txt"
git diff --binary > "$output/source.patch"
git ls-files -c -o --exclude-standard -z | xargs -0 shasum -a 256 > "$output/source-files.sha256"
./scripts/check-all.sh --all > "$output/host.log" 2>&1
./gradlew :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest > "$output/test-apks.log" 2>&1
python3 scripts/debug/2026-09-22/accept-ui-refactor.py --output "$output/devices" "${@:2}" > "$output/devices.log" 2>&1
