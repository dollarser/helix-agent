#!/usr/bin/env bash
# Shared host slot required; verifies the shared synchronous output-import path.
set -euo pipefail
cd "$(dirname "$0")/../../.."
prefix="${1:?new evidence prefix}"
./scripts/check-all.sh --all
./gradlew :app:assembleDeveloperDebugAndroidTest
bash scripts/debug/2026-09-19/run-linux-output-regression.sh "$prefix"
