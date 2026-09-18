#!/usr/bin/env bash
# Final HXA-195 device matrix, sequential to keep emulator resource ownership clear.
set -euo pipefail
bash scripts/debug/2026-09-18/run-log-device.sh 29 build/195/device-api29-final
bash scripts/debug/2026-09-18/run-log-device.sh 36 build/195/device-api36-final
python3 scripts/verify-integrated-runtimes.py \
  --avd HelixApkUpgrade_API29_20260918 --port 5670 --memory-mb 4096 --cores 4 \
  --output build/195/integrated-api29
python3 scripts/verify-integrated-runtimes.py \
  --avd HelixApkUpgrade_API36_20260918 --port 5672 --memory-mb 4096 --cores 4 \
  --output build/195/integrated-api36
