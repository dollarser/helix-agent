#!/usr/bin/env bash
# Run androidTest classes one at a time on a connected device/emulator.
#
# Converged single-writer runner:
# - Enforces single runner lock (.runner.lock + run.json)
# - Writes atomic per-class results (results/<cls>.json + logs/<cls>.log)
# - Performs crash detection & recovery probe after process crashes
# - Single-threaded aggregation generates summary.tsv + summary.json
#
# Usage: run-isolated.sh <classes-file> <out-dir> [flavor] [serial]
#   classes-file: one fully-qualified test class per line (or manifest.json)
#   out-dir:      where per-class logs + summary.tsv are written
#   flavor:       consumer (default) | developer
#   serial:       emulator-5554 (default)
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"

CLASSES_FILE="${1:?classes file required}"
OUT_DIR="${2:?out dir required}"
FLAVOR="${3:-consumer}"
SERIAL="${4:-emulator-5554}"

python3 "$SCRIPT_DIR/run-isolated.py" \
  "$CLASSES_FILE" \
  --out-dir "$OUT_DIR" \
  --flavor "$FLAVOR" \
  --serial "$SERIAL"
