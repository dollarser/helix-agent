#!/usr/bin/env bash
# Shared host slot required; detached and synchronous lanes use the same immutable build.
set -euo pipefail
cd "$(dirname "$0")/../../.."
prefix="${1:?new evidence prefix}"
bash scripts/debug/2026-09-19/run-detached-control-gates.sh "${prefix}-jobs" 31 5658
bash scripts/debug/2026-09-19/run-linux-output-regression.sh "${prefix}-sync"
