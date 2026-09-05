#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
lock="$repo_root/runtime/cli-app/src/main/assets/cli/cli-runtime-lock.json"
serial="${ANDROID_SERIAL:-}"
spike_dir="$(mktemp -d)"
trap 'find "$spike_dir" -type f -delete; find "$spike_dir" -depth -type d -empty -delete' EXIT

artifact_id="claude-code-linux-arm64-musl"
url="$(jq -er --arg id "$artifact_id" '.artifacts[] | select(.id == $id) | .url' "$lock")"
expected_sha="$(jq -er --arg id "$artifact_id" '.artifacts[] | select(.id == $id) | .sha256' "$lock")"
expected_size="$(jq -er --arg id "$artifact_id" '.artifacts[] | select(.id == $id) | .size' "$lock")"
archive="$spike_dir/claude-code.tgz"
binary="$spike_dir/package/claude"

curl --fail --location --silent --show-error --retry 3 "$url" --output "$archive"
test "$(stat -f %z "$archive")" = "$expected_size"
test "$(shasum -a 256 "$archive" | awk '{print $1}')" = "$expected_sha"
tar -tzf "$archive" | sort | diff -u - <(
    printf '%s\n' package/LICENSE.md package/README.md package/claude package/package.json | sort
)
tar -xzf "$archive" -C "$spike_dir"
file "$binary" | grep -F 'ELF 64-bit LSB executable, ARM aarch64' >/dev/null
file "$binary" | grep -F 'interpreter /lib/ld-musl-aarch64.so.1' >/dev/null
grep -F '"os": ["linux"]' "$spike_dir/package/package.json" >/dev/null
grep -F '"libc": ["musl"]' "$spike_dir/package/package.json" >/dev/null

if [[ -z "$serial" ]]; then
    echo "Claude Code Linux arm64-musl artifact verified; set ANDROID_SERIAL for the expected Android loader failure probe"
    exit 0
fi

test "$(adb -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')" = "arm64-v8a"
remote_binary="/data/local/tmp/helix-hxa112-claude"
remote_home="/data/local/tmp/helix-hxa112-home"
adb -s "$serial" push "$binary" "$remote_binary" >/dev/null
adb -s "$serial" shell chmod 755 "$remote_binary"
set +e
probe_output="$(adb -s "$serial" shell "HOME=$remote_home timeout 15 $remote_binary --version" 2>&1 | tr -d '\r')"
probe_status=$?
set -e
adb -s "$serial" shell rm -rf "$remote_binary" "$remote_home"
test "$probe_status" -ne 0
printf '%s\n' "$probe_output" | grep -F 'No such file or directory' >/dev/null

echo "Claude Code direct Android probe rejected as expected on $serial (API $(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r'), arm64-v8a): missing official musl loader; no compatibility layer installed"
