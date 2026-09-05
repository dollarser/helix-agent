#!/usr/bin/env bash
set -euo pipefail

readonly project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

if [[ $# -ne 3 ]]; then
    printf 'Usage: %s <physical-device-serial> <denied|granted> <root-manager-and-version>\n' "$0" >&2
    exit 2
fi

readonly device_serial="$1"
readonly expected_root="$2"
readonly root_manager_evidence="$3"

if [[ "$expected_root" != "denied" && "$expected_root" != "granted" ]]; then
    printf 'Expected Root outcome must be denied or granted, got: %s\n' "$expected_root" >&2
    exit 2
fi

if ! adb devices | awk -v serial="$device_serial" '$1 == serial && $2 == "device" { found = 1 } END { exit !found }'; then
    printf 'Physical test device is not online: %s\n' "$device_serial" >&2
    exit 2
fi

readonly is_emulator="$(adb -s "$device_serial" shell getprop ro.kernel.qemu | tr -d '\r')"
if [[ "$is_emulator" == "1" || "$device_serial" == emulator-* ]]; then
    printf 'HXA-094 rooted acceptance refuses emulator evidence: %s\n' "$device_serial" >&2
    exit 2
fi

readonly api_level="$(adb -s "$device_serial" shell getprop ro.build.version.sdk | tr -d '\r')"
readonly primary_abi="$(adb -s "$device_serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
if [[ ! "$api_level" =~ ^[0-9]+$ ]] || ((api_level < 34)); then
    printf 'HXA-094 requires API 34+, got: %s\n' "$api_level" >&2
    exit 2
fi
if [[ "$primary_abi" != "arm64-v8a" ]]; then
    printf 'HXA-094 requires an arm64-v8a primary ABI, got: %s\n' "$primary_abi" >&2
    exit 2
fi
if [[ -z "${root_manager_evidence// }" ]]; then
    printf 'Root manager name/version evidence must not be empty.\n' >&2
    exit 2
fi

printf 'HXA-094 rooted phase: serial=%s api=%s abi=%s outcome=%s manager=%s\n' \
    "$device_serial" "$api_level" "$primary_abi" "$expected_root" "$root_manager_evidence"
printf 'Use the Root manager UI to %s the explicit Helix prompt when it appears.\n' "$expected_root"

cd "$project_root"
ANDROID_SERIAL="$device_serial" ./gradlew --no-daemon --max-workers=2 \
    "-Pandroid.testInstrumentationRunnerArguments.hxa094ExpectedRoot=$expected_root" \
    :tools:root:connectedDebugAndroidTest

printf 'HXA-094 rooted %s phase passed on %s (%s).\n' \
    "$expected_root" "$device_serial" "$root_manager_evidence"
