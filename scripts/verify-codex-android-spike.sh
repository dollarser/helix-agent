#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
lock="$repo_root/runtime/cli-app/src/main/assets/cli/cli-runtime-lock.json"
serial="${ANDROID_SERIAL:-}"
spike_dir="$(mktemp -d)"
trap 'find "$spike_dir" -type f -delete; rmdir "$spike_dir"' EXIT

url="$(jq -er '.artifacts[] | select(.id == "codex-app-server") | .url' "$lock")"
expected_sha="$(jq -er '.artifacts[] | select(.id == "codex-app-server") | .sha256' "$lock")"
expected_size="$(jq -er '.artifacts[] | select(.id == "codex-app-server") | .size' "$lock")"
archive="$spike_dir/codex-app-server.tar.gz"
binary="$spike_dir/codex-app-server-aarch64-unknown-linux-musl"

curl --fail --location --silent --show-error --retry 3 "$url" --output "$archive"
test "$(stat -f %z "$archive")" = "$expected_size"
test "$(shasum -a 256 "$archive" | awk '{print $1}')" = "$expected_sha"
test "$(tar -tzf "$archive")" = "codex-app-server-aarch64-unknown-linux-musl"
tar -xzf "$archive" -C "$spike_dir"
file "$binary" | grep -F 'ELF 64-bit LSB executable, ARM aarch64' >/dev/null
file "$binary" | grep -F 'statically linked' >/dev/null

if [[ -z "$serial" ]]; then
    echo "Codex artifact verified; set ANDROID_SERIAL for the Android execution probe"
    exit 0
fi

test "$(adb -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')" = "arm64-v8a"
remote_binary="/data/local/tmp/helix-hxa111-codex-app-server"
remote_home="/data/local/tmp/helix-hxa111-home"
adb -s "$serial" push "$binary" "$remote_binary" >/dev/null
adb -s "$serial" shell chmod 755 "$remote_binary"
version_output="$(adb -s "$serial" shell "HOME=$remote_home $remote_binary --version" | tr -d '\r')"
printf '%s\n' "$version_output" | grep -F 'codex-app-server 0.153.3' >/dev/null
initialize_output="$(
    adb -s "$serial" shell \
        "mkdir -p $remote_home && (printf '{\"method\":\"initialize\",\"id\":1,\"params\":{\"clientInfo\":{\"name\":\"helix-hxa111-spike\",\"title\":\"Helix HXA-111 Spike\",\"version\":\"0.1.0\"},\"capabilities\":{}}}\\n{\"method\":\"initialized\",\"params\":{}}\\n'; sleep 2) | HOME=$remote_home timeout 8 $remote_binary --listen stdio:// 2>/dev/null" \
        | tr -d '\r'
)"
printf '%s\n' "$initialize_output" | grep -F '"id":1,"result"' >/dev/null
printf '%s\n' "$initialize_output" | grep -F '"platformOs":"linux"' >/dev/null
adb -s "$serial" shell rm -rf "$remote_binary" "$remote_home"

echo "Codex Android kernel probe passed on $serial (API $(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r'), arm64-v8a); this is experimental compatibility, not vendor Android support"
