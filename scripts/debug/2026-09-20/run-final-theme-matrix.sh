#!/usr/bin/env bash
# Run under the shared host slot after host gates and both test APKs are built.
set -euo pipefail
cd "$(dirname "$0")/../../.."
prefix="${1:?new evidence prefix}"
port="${2:?first free even emulator port}"
for variant in developer consumer; do
  for api in 29 36; do
    python3 scripts/debug/2026-09-18/run-191-theme-device.py \
      "$variant" "$api" "$port" "build/${prefix}-${variant}-api${api}"
    port=$((port + 2))
  done
  count=7
  if [[ "$variant" == developer ]]; then count=8; fi
  python3 scripts/debug/2026-09-20/summarize-subscription-theme.py "${prefix}-${variant}" "$count"
done
