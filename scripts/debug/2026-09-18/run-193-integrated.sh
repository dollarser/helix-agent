#!/usr/bin/env bash
# HXA-193 closeout: run the existing 32-scenario integrated Runtime suite on an OWNED
# fresh AVD + unique port. Delegates to the canonical scripts/verify-integrated-runtimes.py.
#
# Usage: run-193-integrated.sh <avd> <port> <output-dir>
#   e.g. run-193-integrated.sh Helix193_API36 5638 build/193-closeout/api36-fresh
#
# This is a wrapper for provenance; the 32 cases + owned-emulator lifecycle live in
# verify-integrated-runtimes.py and scripts/debug/2026-09-09/run-owned-emulator.py.
set -euo pipefail
# Script lives at scripts/debug/<date>/run-193-integrated.sh -> repo root is 3 levels up.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$REPO_ROOT"
[ -f scripts/verify-integrated-runtimes.py ] || { echo "verify-integrated-runtimes.py not found from $REPO_ROOT" >&2; exit 1; }

AVD="${1:?avd}"
PORT="${2:?port}"
OUT="${3:?output-dir}"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
[ -z "${JAVA_HOME:-}" ] && [ -x /opt/homebrew/opt/openjdk@17/bin/java ] && \
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home

python3 scripts/verify-integrated-runtimes.py --avd "$AVD" --port "$PORT" --output "$OUT"
