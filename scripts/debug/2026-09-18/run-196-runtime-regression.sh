#!/usr/bin/env bash
# Regression on the changed runner; each invocation owns and closes a new emulator.
set -euo pipefail
cd "$(dirname "$0")/../../.."
python3 scripts/verify-integrated-runtimes.py --avd HelixApkUpgrade_API29_20260918 --port 5642 --memory-mb 4096 --cores 4 --output build/hxa196-regression-api29
python3 scripts/verify-integrated-runtimes.py --avd HelixApkUpgrade_API36_20260918 --port 5644 --memory-mb 4096 --cores 4 --output build/hxa196-regression-api36
