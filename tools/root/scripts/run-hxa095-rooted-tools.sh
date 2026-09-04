#!/usr/bin/env bash
set -euo pipefail

readonly project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

if [[ $# -ne 2 ]]; then
    printf 'Usage: %s <physical-device-serial> <root-manager-and-version>\n' "$0" >&2
    exit 2
fi

readonly device_serial="$1"
readonly root_manager_evidence="$2"

if ! adb devices | awk -v serial="$device_serial" '$1 == serial && $2 == "device" { found = 1 } END { exit !found }'; then
    printf 'Physical test device is not online: %s\n' "$device_serial" >&2
    exit 2
fi

readonly is_emulator="$(adb -s "$device_serial" shell getprop ro.kernel.qemu | tr -d '\r')"
if [[ "$is_emulator" == "1" || "$device_serial" == emulator-* ]]; then
    printf 'HXA-095 rooted acceptance refuses emulator evidence: %s\n' "$device_serial" >&2
    exit 2
fi

readonly api_level="$(adb -s "$device_serial" shell getprop ro.build.version.sdk | tr -d '\r')"
readonly primary_abi="$(adb -s "$device_serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
if [[ ! "$api_level" =~ ^[0-9]+$ ]] || ((api_level < 34)); then
    printf 'HXA-095 requires API 34+, got: %s\n' "$api_level" >&2
    exit 2
fi
if [[ "$primary_abi" != "arm64-v8a" ]]; then
    printf 'HXA-095 requires an arm64-v8a primary ABI, got: %s\n' "$primary_abi" >&2
    exit 2
fi
if [[ -z "${root_manager_evidence// }" ]]; then
    printf 'Root manager name/version evidence must not be empty.\n' >&2
    exit 2
fi

printf 'HXA-095 rooted tools: serial=%s api=%s abi=%s manager=%s\n' \
    "$device_serial" "$api_level" "$primary_abi" "$root_manager_evidence"
printf 'Use the Root manager UI to grant the explicit Helix prompt when it appears.\n'

cd "$project_root"
ANDROID_SERIAL="$device_serial" ./gradlew --no-daemon --max-workers=2 \
    -Pandroid.testInstrumentationRunnerArguments.hxa095ExpectedRoot=granted \
    -Pandroid.testInstrumentationRunnerArguments.class=com.helix.tools.root.RootHighLevelToolsDeviceTest \
    :tools:root:connectedDebugAndroidTest

printf 'HXA-095 rooted high-level tool matrix passed on %s (%s).\n' \
    "$device_serial" "$root_manager_evidence"
