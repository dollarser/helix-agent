#!/usr/bin/env bash
set -euo pipefail

readonly project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
if [[ $# -ne 2 ]]; then
    printf 'Usage: %s <emulator-serial> <root-manager-and-version>\n' "$0" >&2
    exit 2
fi
readonly device_serial="$1"
readonly manager_evidence="$2"
readonly qemu="$(adb -s "$device_serial" shell getprop ro.kernel.qemu | tr -d '\r')"
[[ "$qemu" == 1 && "$device_serial" == emulator-* ]] || { printf 'Emulator-only experiment refuses: %s\n' "$device_serial" >&2; exit 2; }
readonly api="$(adb -s "$device_serial" shell getprop ro.build.version.sdk | tr -d '\r')"
readonly abi="$(adb -s "$device_serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
[[ "$api" =~ ^[0-9]+$ ]] && ((api >= 34)) || { printf 'API 34+ required, got %s\n' "$api" >&2; exit 2; }
[[ "$abi" == arm64-v8a ]] || { printf 'arm64-v8a required, got %s\n' "$abi" >&2; exit 2; }
[[ -n "${manager_evidence// }" ]] || { printf 'Root manager evidence is required.\n' >&2; exit 2; }

printf 'EVIDENCE serial=%s avd=%s api=%s abi=%s pageSize=%s fingerprint=%s manager=%s\n' \
    "$device_serial" "$(adb -s "$device_serial" emu avd name | tr -d '\r' | head -n 1)" "$api" "$abi" \
    "$(adb -s "$device_serial" shell getconf PAGE_SIZE | tr -d '\r')" \
    "$(adb -s "$device_serial" shell getprop ro.build.fingerprint | tr -d '\r')" "$manager_evidence"
printf 'Grant com.helix.tools.root.test in the Root manager. This script never injects policy with adb shell su.\n'

cd "$project_root"
./gradlew --no-daemon --max-workers=2 :tools:root:assembleDebugAndroidTest --no-configuration-cache
adb -s "$device_serial" install -r -t tools/root/build/outputs/apk/androidTest/debug/root-debug-androidTest.apk >/dev/null
readonly output_file="$(mktemp "${TMPDIR:-/tmp}/hxa095-emulator.XXXXXX")"
trap 'rm -f "$output_file"' EXIT
adb -s "$device_serial" shell am instrument -w -r \
    -e hxa095ExpectedRoot granted \
    -e class com.helix.tools.root.RootHighLevelToolsDeviceTest \
    com.helix.tools.root.test/androidx.test.runner.AndroidJUnitRunner | tee "$output_file"
grep -Eq '^OK \(1 test\)$' "$output_file" || { printf 'JUnit OK (1 test) marker missing.\n' >&2; exit 1; }
! grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -2|-4' "$output_file" || { printf 'JUnit failure/skip marker found.\n' >&2; exit 1; }
printf 'HXA-095 rooted emulator tool matrix passed. This is emulator evidence only.\n'
