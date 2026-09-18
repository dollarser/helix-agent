#!/usr/bin/env bash
# HXA-196 unchanged-source Runtime baseline; each run owns a fresh emulator.
set -euo pipefail
cd "$(dirname "$0")/../../.."
python3 scripts/verify-integrated-runtimes.py --avd HelixApkUpgrade_API29_20260918 --port 5630 --memory-mb 4096 --cores 4 --output build/hxa196-baseline-api29
python3 scripts/verify-integrated-runtimes.py --avd HelixApkUpgrade_API36_20260918 --port 5632 --memory-mb 4096 --cores 4 --output build/hxa196-baseline-api36
