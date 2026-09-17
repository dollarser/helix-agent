#!/bin/sh
# HXA-209 D8 device acceptance, ONE quadrant (variant + api + port).
#
# Runs the three new session-permission classes on an EXCLUSIVE emulator (1080x2400@420, refuses a
# reused serial, always closes only its own process group), driving the two-phase process-recovery
# protocol through the owned runner:
#   - recovery setup : com.helix.app.SessionPermissionRecoveryDeviceTest with recoveryPhase=setup
#                      persists the stored config + a no-config session, writes the durable pid
#                      marker, and kills the process (the runner expects "process crashed");
#   - main run       : all three classes with recoveryPhase=verify, ONE install so the persisted
#                      rows survive into the verify process (no cross-Gradle uninstall).
#
# The three classes assert BOTH the side effect (did the tool run / was the row created / did the
# write target appear?) and the availability decision — never the outcome code alone.
#
# Usage: run-hxa209-d8-device.sh <consumer|developer> <29|36> <even port 5554-5682> <output dir>
set -eu
variant=$1
api=$2
port=$3
output=$4
suffix=
if [ "$variant" = developer ]; then suffix=.developer; fi
classes="com.helix.app.SessionPermissionDeviceTest,com.helix.app.ExecutionEffectBoundaryDeviceTest,com.helix.app.SessionPermissionRecoveryDeviceTest"
recovery="com.helix.app.SessionPermissionRecoveryDeviceTest"
python3 scripts/debug/2026-09-09/run-owned-emulator.py --avd "Helix_M11_Test_API_$api" --port "$port" \
 --apk "app/build/outputs/apk/$variant/debug/app-$variant-debug.apk" \
 --test-apk "app/build/outputs/apk/androidTest/$variant/debug/app-$variant-debug-androidTest.apk" \
 --runner "com.helix.agent$suffix.test/com.helix.app.HelixAndroidJUnitRunner" \
 --classes "$classes" --recovery-setup-class "$recovery" --instrument-arg recoveryPhase=verify --output "$output"
