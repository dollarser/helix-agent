#!/usr/bin/env bash
# Fresh device proofs after adding the durable pre-start cancellation outcome.
set -euo pipefail
cd "$(dirname "$0")/../../.."
bash scripts/debug/2026-09-18/run-196-device.sh 29 5646 build/hxa196-cancel-api29
bash scripts/debug/2026-09-18/run-196-device.sh 36 5648 build/hxa196-cancel-api36
