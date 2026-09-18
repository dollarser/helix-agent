#!/usr/bin/env bash
# Invoke through with-host-slot.py. Runtime setup uses the repository's locked assets.
set -euo pipefail
cd "$(dirname "$0")/../../.."
prefix="${1:?new evidence prefix}"
expected="${2:-29}"
port="${3:-5674}"
./scripts/check-all.sh --all
./gradlew :app:assembleDeveloperDebugAndroidTest
bash scripts/debug/2026-09-18/run-job-submission-regression.sh "$prefix" "$port"
python3 scripts/debug/2026-09-18/summarize-job-submission.py "$prefix" "$expected"
