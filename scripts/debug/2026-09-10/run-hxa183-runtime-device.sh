#!/bin/sh
set -eu
api=$1
port=$2
output=$3
python3 scripts/debug/2026-09-09/run-owned-emulator.py --avd "Helix_M11_Test_API_$api" --port "$port" \
 --apk runtime/proot-app/build/outputs/apk/debug/proot-app-debug.apk \
 --test-apk runtime/proot-app/build/outputs/apk/androidTest/debug/proot-app-debug-androidTest.apk \
 --runner com.helix.runtime.proot.test/androidx.test.runner.AndroidJUnitRunner \
 --classes com.helix.runtime.proot.app.ProotLockSchemaDeviceTest --output "$output" --timeout 1500 \
 --after-script scripts/debug/2026-09-10/run-hxa183-proot.py
