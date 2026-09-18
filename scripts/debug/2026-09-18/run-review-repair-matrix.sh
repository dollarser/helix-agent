#!/usr/bin/env bash
set -euo pipefail
prefix="${1:?new evidence prefix}"
port=5660
for variant in consumer developer; do
  for api in 29 36; do
    bash scripts/debug/2026-09-18/run-review-repair-device.sh "$variant" "$api" "$port" "build/${prefix}-${variant}-${api}"
    port=$((port + 2))
  done
done
