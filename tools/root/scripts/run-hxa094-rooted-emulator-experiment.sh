#!/usr/bin/env bash
set -euo pipefail

readonly project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

if [[ $# -ne 3 ]]; then
    printf 'Usage: %s <emulator-serial> <denied|granted|revoked> <root-manager-and-version>\n' "$0" >&2
    exit 2
fi

readonly device_serial="$1"
readonly expected_root="$2"
readonly manager_evidence="$3"
[[ "$expected_root" =~ ^(denied|granted|revoked)$ ]] || { printf 'Invalid phase: %s\n' "$expected_root" >&2; exit 2; }
[[ -n "${manager_evidence// }" ]] || { printf 'Root manager evidence is required.\n' >&2; exit 2; }

adb -s "$device_serial" get-state | grep -qx device || { printf 'Emulator is not online: %s\n' "$device_serial" >&2; exit 2; }
readonly qemu="$(adb -s "$device_serial" shell getprop ro.kernel.qemu | tr -d '\r')"
[[ "$qemu" == 1 && "$device_serial" == emulator-* ]] || { printf 'Emulator-only experiment refuses: %s\n' "$device_serial" >&2; exit 2; }
readonly api="$(adb -s "$device_serial" shell getprop ro.build.version.sdk | tr -d '\r')"
readonly abi="$(adb -s "$device_serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
[[ "$api" =~ ^[0-9]+$ ]] && ((api >= 34)) || { printf 'API 34+ required, got %s\n' "$api" >&2; exit 2; }
[[ "$abi" == arm64-v8a ]] || { printf 'arm64-v8a required, got %s\n' "$abi" >&2; exit 2; }

readonly fingerprint="$(adb -s "$device_serial" shell getprop ro.build.fingerprint | tr -d '\r')"
readonly build_type="$(adb -s "$device_serial" shell getprop ro.build.type | tr -d '\r')"
readonly page_size="$(adb -s "$device_serial" shell getconf PAGE_SIZE | tr -d '\r')"
readonly manager_version="$(adb -s "$device_serial" shell dumpsys package com.topjohnwu.magisk 2>/dev/null | sed -n 's/.*versionName=//p' | head -n 1)"
[[ -n "$manager_version" ]] || { printf 'Magisk manager is not installed.\n' >&2; exit 2; }

printf 'EVIDENCE serial=%s avd=%s api=%s abi=%s pageSize=%s buildType=%s fingerprint=%s manager=%s installedManagerVersion=%s\n' \
    "$device_serial" "$(adb -s "$device_serial" emu avd name | tr -d '\r' | head -n 1)" "$api" "$abi" "$page_size" \
    "$build_type" "$fingerprint" "$manager_evidence" "$manager_version"
printf 'Set the Magisk manager policy for com.helix.tools.root.test to phase=%s now. Do not use adb shell su to inject policy.\n' "$expected_root"

cd "$project_root"
./gradlew --no-daemon --max-workers=2 :tools:root:assembleDebugAndroidTest --no-configuration-cache
adb -s "$device_serial" install -r -t tools/root/build/outputs/apk/androidTest/debug/root-debug-androidTest.apk >/dev/null
readonly output_file="$(mktemp "${TMPDIR:-/tmp}/hxa094-emulator.XXXXXX")"
trap 'rm -f "$output_file"' EXIT
adb -s "$device_serial" shell am instrument -w -r \
    -e hxa094ExpectedRoot "$expected_root" \
    -e class com.helix.tools.root.LibsuRootAccessDeviceTest \
    com.helix.tools.root.test/androidx.test.runner.AndroidJUnitRunner | tee "$output_file"

grep -Eq '^OK \([0-9]+ tests?\)$' "$output_file" || { printf 'JUnit OK marker missing.\n' >&2; exit 1; }
! grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -2' "$output_file" || { printf 'JUnit failure marker found.\n' >&2; exit 1; }
printf 'HXA-094 rooted emulator phase passed: %s. This is emulator evidence only.\n' "$expected_root"
