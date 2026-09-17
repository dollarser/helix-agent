#!/bin/sh
# HXA-192 device acceptance, ONE quadrant (variant + api + port).
#
# Runs the four Plan closed-loop / 209-linkage classes on an EXCLUSIVE emulator
# (1080x2400@420, refuses a reused serial, always closes only its own process group), driving the
# two-phase process-recovery protocol through the owned runner:
#   - recovery setup : com.helix.app.plan.PlanExecutionRecoveryDeviceTest with recoveryPhase=setup
#                      seeds a fresh plan, drives it READY -> APPROVED -> EXECUTING (creating its
#                      bound goal), writes the durable pid marker, and kills the process (the runner
#                      expects "process crashed");
#   - main run       : all four classes with recoveryPhase=verify, ONE install so the persisted
#                      EXECUTING plan survives into the verify process (no cross-Gradle uninstall).
#
# The four classes assert BOTH the side effect (did the goal bind? did the tool run? was a card
# minted? what is the durable call/plan state?) and the availability/authorization decision — never
# the outcome code alone.
#
# Usage: run-hxa192-device.sh <consumer|developer> <29|36> <even port 5554-5682> <output dir>
set -eu
variant=$1
api=$2
port=$3
output=$4
suffix=
if [ "$variant" = developer ]; then suffix=.developer; fi
classes="com.helix.app.plan.PlanExecuteCloseLoopDeviceTest,com.helix.app.plan.PlanExecutionAcceptanceDeviceTest,com.helix.app.plan.PlanAuthorizationLinkageDeviceTest,com.helix.app.plan.PlanExecutionRecoveryDeviceTest"
recovery="com.helix.app.plan.PlanExecutionRecoveryDeviceTest"
python3 scripts/debug/2026-09-09/run-owned-emulator.py --avd "Helix_M11_Test_API_$api" --port "$port" \
 --apk "app/build/outputs/apk/$variant/debug/app-$variant-debug.apk" \
 --test-apk "app/build/outputs/apk/androidTest/$variant/debug/app-$variant-debug-androidTest.apk" \
 --runner "com.helix.agent$suffix.test/com.helix.app.HelixAndroidJUnitRunner" \
 --classes "$classes" --recovery-setup-class "$recovery" --instrument-arg recoveryPhase=verify --timeout 1200 --output "$output"
