#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
lock="$repo_root/runtime/cli-app/src/main/assets/cli/cli-runtime-lock.json"
serial="${ANDROID_SERIAL:-}"
spike_dir="$(mktemp -d)"
trap 'find "$spike_dir" -type f -delete; find "$spike_dir" -depth -type d -empty -delete' EXIT

verify_artifact() {
  local id="$1"
  local archive="$2"
  local url expected_sha expected_size
  url="$(jq -er --arg id "$id" '.artifacts[] | select(.id == $id) | .url' "$lock")"
  expected_sha="$(jq -er --arg id "$id" '.artifacts[] | select(.id == $id) | .sha256' "$lock")"
  expected_size="$(jq -er --arg id "$id" '.artifacts[] | select(.id == $id) | .size' "$lock")"
  curl --fail --location --silent --show-error --retry 3 "$url" --output "$archive"
  test "$(stat -f %z "$archive")" = "$expected_size"
  test "$(shasum -a 256 "$archive" | awk '{print $1}')" = "$expected_sha"
}

sdk_archive="$spike_dir/sdk.tgz"
glibc_archive="$spike_dir/linux-arm64.tgz"
musl_archive="$spike_dir/linuxmusl-arm64.tgz"
verify_artifact github-copilot-sdk-npm "$sdk_archive"
verify_artifact github-copilot-sdk-linux-arm64 "$glibc_archive"
verify_artifact github-copilot-sdk-linuxmusl-arm64 "$musl_archive"

mkdir "$spike_dir/sdk" "$spike_dir/glibc" "$spike_dir/musl"
tar -xzf "$sdk_archive" -C "$spike_dir/sdk"
tar -xzf "$glibc_archive" -C "$spike_dir/glibc"
tar -xzf "$musl_archive" -C "$spike_dir/musl"

grep -F '"copilotCliVersion": "1.0.83"' "$spike_dir/sdk/package/package.json" >/dev/null
grep -F '"node": "^20.19.0 || >=22.12.0"' "$spike_dir/sdk/package/package.json" >/dev/null
grep -F '"libc": [' "$spike_dir/glibc/package/package.json" >/dev/null
grep -F '"glibc"' "$spike_dir/glibc/package/package.json" >/dev/null
grep -F '"musl"' "$spike_dir/musl/package/package.json" >/dev/null

glibc_binary="$spike_dir/glibc/package/prebuilds/linux-arm64/copilot-runtime"
musl_binary="$spike_dir/musl/package/prebuilds/linuxmusl-arm64/copilot-runtime"
file "$glibc_binary" | grep -F 'ELF 64-bit LSB pie executable, ARM aarch64' >/dev/null
file "$glibc_binary" | grep -F 'interpreter /lib/ld-linux-aarch64.so.1' >/dev/null
file "$musl_binary" | grep -F 'ELF 64-bit LSB pie executable, ARM aarch64' >/dev/null
file "$musl_binary" | grep -F 'interpreter /lib/ld-musl-aarch64.so.1' >/dev/null

if [[ -z "$serial" ]]; then
  echo "GitHub Copilot SDK 1.0.13 official Linux arm64 artifacts verified; set ANDROID_SERIAL for expected loader failures"
  exit 0
fi

test "$(adb -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')" = "arm64-v8a"
for flavor in glibc musl; do
  binary_var="${flavor}_binary"
  binary="${!binary_var}"
  remote="/data/local/tmp/helix-hxa117-copilot-$flavor"
  adb -s "$serial" push "$binary" "$remote" >/dev/null
  adb -s "$serial" shell chmod 755 "$remote"
  set +e
  output="$(adb -s "$serial" shell "timeout 15 $remote --version" 2>&1 | tr -d '\r')"
  status=$?
  set -e
  adb -s "$serial" shell rm -f "$remote"
  test "$status" -ne 0
  printf '%s\n' "$output" | grep -F 'No such file or directory' >/dev/null
done

echo "GitHub Copilot SDK direct Android probe rejected as expected on $serial (API $(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r'), arm64-v8a): official runtimes require glibc or musl loaders"
