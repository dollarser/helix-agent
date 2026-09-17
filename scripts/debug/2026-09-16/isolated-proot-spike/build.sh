#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../../../../"
python3 scripts/debug/2026-09-16/isolated-proot-spike/build.py
