#!/usr/bin/env bash
# HXA-241 only: finish current-build recovery evidence using the existing owned-emulator driver.
# Invoke under with-host-slot; no real devices, external accounts, source mutation or Git writes.
set -euo pipefail
mkdir -p build/hxa241
python3 scripts/debug/2026-10-02/freeze-hxa241-device-inputs.py verify > build/hxa241/recovery-freeze-before.log
python3 -u scripts/accept-conversation-interaction.py --scope 216 --api 36 --only recovery --first-port 5590 \
  --output build/hxa241/emulator-20261002/input-recovery-final-build \
  > build/hxa241/recovery-final-build.log 2>&1
python3 -u scripts/accept-conversation-interaction.py --scope 215 --api 36 --only recovery --first-port 5618 \
  --output build/hxa241/emulator-20261002/revision-final-build \
  > build/hxa241/revision-final-build.log 2>&1
python3 scripts/debug/2026-10-02/freeze-hxa241-device-inputs.py verify > build/hxa241/recovery-freeze-after.log
printf 'HXA-241 final-build queue and message-revision emulator recovery matrices passed.\n'
