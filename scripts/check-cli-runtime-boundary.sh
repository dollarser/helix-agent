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
printf '%s\n' "$manifest" | grep -F 'com.helix.runtime.cli.app.CopilotLoginActivity' >/dev/null
printf '%s\n' "$manifest" | grep -F 'com.helix.runtime.cli.app.ClaudeLoginActivity' >/dev/null
printf '%s\n' "$manifest" | grep -F 'com.helix.permission.BIND_CLI_RUNTIME' >/dev/null

if rg -n 'chatgpt\.com/backend-api|api\.anthropic\.com/v1/messages|api\.x\.ai|api\.githubcopilot\.com' \
    "$repo_root/runtime/cli-app/src/main"; then
    echo "model subscription endpoint entered HXA-118 login-only scope" >&2
    exit 1
fi
test "$(rg -F 'https://api.anthropic.com/api/oauth/profile' "$repo_root/runtime/cli-app/src/main" | wc -l | tr -d ' ')" = 1
if rg -n 'CookieManager|content://|\.codex/auth|[/"]\.claude([/" ]|$)' "$repo_root/runtime/cli-app/src/main/kotlin"; then
    echo "external credential import signal entered CLI Runtime" >&2
    exit 1
fi

rg -F 'Iv1.b507a08c87ecfe98' "$repo_root/runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/CopilotDeviceOAuth.kt" >/dev/null
rg -F 'https://api.github.com/copilot_internal/v2/token' "$repo_root/runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/CopilotDeviceOAuth.kt" >/dev/null

echo "CLI Runtime HXA-124 boundary: auth/eligibility-only, fixed sideload identities, network-only, no model endpoint or credential import"
