#!/bin/sh
# HXA-202 slice 4 device acceptance, ONE quadrant (variant + api + port).
#
# Runs the full task-journey class on an EXCLUSIVE emulator (1080x2400@420, refuses a reused
# serial, finally closes only its own process group) through the owned runner:
#   - recovery setup : com.helix.app.TaskJourneyDeviceTest with recoveryPhase=setup seeds the
#                      journey history with FIXED ids, records the process ID at the runner's
#                      durable marker path and kills the process (the runner expects
#                      "process crashed");
#   - main run       : the same class with recoveryPhase=verify — the recovery method asserts
#                      a NEW PID with the history, artifact ownership and goal states intact
#                      and no new turn or goal run; the other methods `assumeTrue`-skip in
#                      the setup run, so every method genuinely executes exactly once per
#                      quadrant and a skip is never a pass.
#
# Usage: run-hxa202-device.sh <consumer|developer> <29|36> <even port 5554-5682> <output dir>
set -eu
variant=$1
api=$2
port=$3
output=$4
suffix=
if [ "$variant" = developer ]; then suffix=.developer; fi
classes="com.helix.app.TaskJourneyDeviceTest"
recovery="com.helix.app.TaskJourneyDeviceTest"
python3 scripts/debug/2026-09-09/run-owned-emulator.py --avd "Helix_M11_Test_API_$api" --port "$port" \
 --apk "app/build/outputs/apk/$variant/debug/app-$variant-debug.apk" \
 --test-apk "app/build/outputs/apk/androidTest/$variant/debug/app-$variant-debug-androidTest.apk" \
 --runner "com.helix.agent$suffix.test/com.helix.app.HelixAndroidJUnitRunner" \
 --classes "$classes" --recovery-setup-class "$recovery" --instrument-arg recoveryPhase=verify --output "$output"
