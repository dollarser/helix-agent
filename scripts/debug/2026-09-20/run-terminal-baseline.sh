#!/usr/bin/env bash
# Run under with-host-slot.py against APKs already built by the full host gate.
set -euo pipefail
cd "$(dirname "$0")/../../.."
prefix="${1:?new evidence prefix}"
port="${2:?first free even port}"
for api in 29 36; do
  python3 scripts/verify-integrated-runtimes.py \
    --avd "HelixApkUpgrade_API${api}_20260918" --port "$port" \
    --memory-mb 4096 --cores 4 --output "build/${prefix}-api${api}"
  port=$((port + 2))
done
python3 scripts/debug/2026-09-18/summarize-job-submission.py "$prefix" 37
