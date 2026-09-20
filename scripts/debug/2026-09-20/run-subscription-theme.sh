#!/usr/bin/env bash
# Run under with-host-slot.py after the full host gate and developer test APK build.
set -euo pipefail
cd "$(dirname "$0")/../../.."
prefix="${1:?new evidence prefix}"
port="${2:?first free even emulator port}"
for api in 29 36; do
  python3 scripts/debug/2026-09-18/run-191-theme-device.py \
    developer "$api" "$port" "build/${prefix}-api${api}"
  port=$((port + 2))
done
