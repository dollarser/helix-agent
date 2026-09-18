#!/usr/bin/env bash
# Shared host slot required. Keep one immutable build across Goal and Runtime regressions.
set -euo pipefail
cd "$(dirname "$0")/../../.."
prefix="${1:?new evidence prefix}"
bash scripts/debug/2026-09-19/run-goal-lease-gates.sh "${prefix}-goal" 5642
bash scripts/debug/2026-09-18/run-job-submission-regression.sh "${prefix}-job" 5650
python3 scripts/debug/2026-09-18/summarize-job-submission.py "${prefix}-job" 31
