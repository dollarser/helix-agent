#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
apk="$repo_root/runtime/cli-app/build/outputs/apk/debug/cli-app-debug.apk"
build_tools="${ANDROID_HOME:?ANDROID_HOME is required}/build-tools/36.0.0"

test -f "$apk"
permissions="$($build_tools/aapt2 dump permissions "$apk")"
printf '%s\n' "$permissions" | grep -F "android.permission.INTERNET" >/dev/null
if printf '%s\n' "$permissions" | grep -Eq 'MANAGE_EXTERNAL_STORAGE|BIND_ACCESSIBILITY_SERVICE|ACCESS_SUPERUSER'; then
    echo "CLI Runtime gained a forbidden Android permission" >&2
    exit 1
fi

manifest="$($build_tools/aapt2 dump xmltree "$apk" --file AndroidManifest.xml)"
printf '%s\n' "$manifest" | grep -F 'com.helix.runtime.cli.app.CodexLoginActivity' >/dev/null
printf '%s\n' "$manifest" | grep -F 'com.helix.permission.BIND_CLI_RUNTIME' >/dev/null

if rg -n 'chatgpt\.com/backend-api|api\.anthropic\.com|api\.x\.ai|api\.githubcopilot\.com' \
    "$repo_root/runtime/cli-app/src/main"; then
    echo "model subscription endpoint entered HXA-118 login-only scope" >&2
    exit 1
fi
if rg -n 'CookieManager|content://|\.codex/auth|\.claude' "$repo_root/runtime/cli-app/src/main/kotlin"; then
    echo "external credential import signal entered CLI Runtime" >&2
    exit 1
fi

echo "CLI Runtime HXA-118 boundary: auth-only, network-only, no model endpoint or credential import"
