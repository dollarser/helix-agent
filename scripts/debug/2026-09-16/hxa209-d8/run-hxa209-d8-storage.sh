#!/bin/sh
# HXA-209 D8 device gate, storage side: the complete Room migration suite
# (RoomMigrationFixtureTest — v19->v22 chain + v22 export drift guard) on an EXCLUSIVE
# emulator (1080x2400@420, refuses a reused serial, always closes only its own process
# group). core:storage is flavor-agnostic, so ONE test APK per API level covers both
# flavors. The storage androidTest APK is self-contained (its own
# com.helix.core.storage.test applicationId), so both --apk and --test-apk point at it.
#
# Usage: run-hxa209-d8-storage.sh <29|36> <even port 5554-5682> <output dir>
set -eu
api=$1
port=$2
output=$3
storage_apk="core/storage/build/outputs/apk/androidTest/debug/storage-debug-androidTest.apk"
python3 scripts/debug/2026-09-09/run-owned-emulator.py --avd "Helix_M11_Test_API_$api" --port "$port" \
 --apk "$storage_apk" --test-apk "$storage_apk" \
 --runner "com.helix.core.storage.test/androidx.test.runner.AndroidJUnitRunner" \
 --classes "com.helix.core.storage.RoomMigrationFixtureTest" --output "$output"
