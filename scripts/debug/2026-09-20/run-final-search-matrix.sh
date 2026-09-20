#!/usr/bin/env bash
# Run under the shared host slot; each quadrant includes real main-process recovery and Room queries.
set -euo pipefail
cd "$(dirname "$0")/../../.."
prefix="${1:?new evidence prefix}"
port="${2:?first free even emulator port}"
for variant in developer consumer; do
  for api in 29 36; do
    output="build/${prefix}-${variant}-api${api}"
    test ! -e "$output"
    bash scripts/debug/2026-09-18/run-191-search-device.sh "$variant" "$api" "$port" "$output"
    port=$((port + 2))
  done
  python3 scripts/debug/2026-09-18/summarize-job-submission.py "${prefix}-${variant}" 8
done
