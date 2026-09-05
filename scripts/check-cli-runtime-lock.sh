#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
lock="$repo_root/runtime/cli-app/src/main/assets/cli/cli-runtime-lock.json"
apk="$repo_root/runtime/cli-app/build/outputs/apk/debug/cli-app-debug.apk"
verify_dir="$(mktemp -d)"
trap 'find "$verify_dir" -type f -delete; rmdir "$verify_dir"' EXIT

jq -e '
  .lockVersion == 1 and .abi == "arm64-v8a" and
  ([.artifacts[].id] | sort) == (["claude-code-linux-arm64-musl", "claude-code-npm", "codex-app-server", "node"] | sort) and
  all(.artifacts[]; .bundled == false and (.sha256 | test("^[0-9a-f]{64}$")) and
      (.url | startswith("https://")) and (.licenseUrl | startswith("https://")) and
      (.termsUrl | startswith("https://")))
' "$lock" >/dev/null

while IFS=$'\t' read -r id url expected; do
    target="$verify_dir/$id"
    curl --fail --location --silent --show-error "$url" --output "$target"
    actual="$(shasum -a 256 "$target" | awk '{print $1}')"
    test "$actual" = "$expected"
done < <(jq -r '.artifacts[] | [.id, .url, .sha256] | @tsv' "$lock")

for url in $(jq -r '.artifacts[] | .licenseUrl, .termsUrl' "$lock"); do
    status="$(curl --location --silent --show-error --output /dev/null --write-out '%{http_code}' --range 0-0 "$url")"
    case "$status" in
        200|206) ;;
        403) echo "legal URL blocks automated retrieval (recorded, URL retained): $url" ;;
        *) echo "legal URL unavailable ($status): $url" >&2; exit 1 ;;
    esac
done

unzip -p "$apk" assets/cli/cli-runtime-lock.json | cmp -s - "$lock"
if unzip -l "$apk" | awk '{print $4}' | grep -Eq '\.(so|node|tgz|tar|tar\.xz)$'; then
    echo "CLI executable/archive unexpectedly bundled before HXA-111/112" >&2
    exit 1
fi

echo "CLI runtime metadata lock: artifact hashes, declared legal URLs, and APK boundary verified"
