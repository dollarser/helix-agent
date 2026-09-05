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
printf '%s\n' "$manifest" | grep -F 'com.helix.runtime.cli.app.GrokLoginActivity' >/dev/null
printf '%s\n' "$manifest" | grep -F 'com.helix.permission.BIND_CLI_RUNTIME' >/dev/null

if rg -n 'chatgpt\.com/backend-api|api\.anthropic\.com/v1/messages|api\.x\.ai|api\.githubcopilot\.com' \
    "$repo_root/runtime/cli-app/src/main" --glob '!**/CodexSubscriptionSmoke.kt'; then
    echo "model subscription endpoint escaped the HXA-127 bounded Codex smoke" >&2
    exit 1
fi
smoke="$repo_root/runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/CodexSubscriptionSmoke.kt"
test "$(rg -F 'https://chatgpt.com/backend-api/codex/models' "$smoke" | wc -l | tr -d ' ')" = 1
test "$(rg -F 'https://chatgpt.com/backend-api/codex/responses' "$smoke" | wc -l | tr -d ' ')" = 1
rg -F 'Reply exactly HELIX_OK' "$smoke" >/dev/null
rg -F 'const val MAX_TEXT_CHARS = 64' "$smoke" >/dev/null
rg -F 'const val MAX_STREAM_BYTES = 256L * 1024L' "$smoke" >/dev/null
test "$(rg -F 'https://api.anthropic.com/api/oauth/profile' "$repo_root/runtime/cli-app/src/main" | wc -l | tr -d ' ')" = 1
if rg -n 'CookieManager|content://|\.codex/auth|[/"]\.claude([/" ]|$)' "$repo_root/runtime/cli-app/src/main/kotlin"; then
    echo "external credential import signal entered CLI Runtime" >&2
    exit 1
fi

rg -F 'Iv1.b507a08c87ecfe98' "$repo_root/runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/CopilotDeviceOAuth.kt" >/dev/null
rg -F 'https://api.github.com/copilot_internal/v2/token' "$repo_root/runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/CopilotDeviceOAuth.kt" >/dev/null
rg -F 'https://auth.x.ai/oauth2/device/code' "$repo_root/runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/GrokDeviceOAuth.kt" >/dev/null
rg -F 'https://auth.openai.com/api/accounts/deviceauth/usercode' "$repo_root/runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/CodexDeviceOAuth.kt" >/dev/null
rg -F 'https://auth.openai.com/api/accounts/deviceauth/token' "$repo_root/runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/CodexDeviceOAuth.kt" >/dev/null
rg -F 'https://auth.openai.com/codex/device' "$repo_root/runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/CodexDeviceOAuth.kt" >/dev/null
rg -F 'vendorAuthorizesHelixDistribution' "$repo_root/runtime/cli-client/src/main/kotlin/com/helix/runtime/cli/client/CliAgentBackendEligibility.kt" >/dev/null
rg -F 'DISTRIBUTION_AUTHORIZATION_UNPROVEN' "$repo_root/runtime/cli-client/src/main/kotlin/com/helix/runtime/cli/client/CliAgentBackendEligibility.kt" >/dev/null
rg -F 'CliProviderChannel.CONSUMER_STORE' "$repo_root/runtime/cli-client/src/main/kotlin/com/helix/runtime/cli/client/CliAgentBackendEligibility.kt" >/dev/null
rg -F 'DEVELOPER_ADVANCED' "$repo_root/runtime/cli-client/src/main/kotlin/com/helix/runtime/cli/client/CliAgentBackendEligibility.kt" >/dev/null
if rg -n 'TRANSACTION_.*JOB|TX_.*JOB' "$repo_root/runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/CliRuntimeProtocol.kt"; then
    echo "subscription adapter exposed a cross-APK model job transaction" >&2
    exit 1
fi

echo "HXA-130 subscription boundary: developer Provider permitted without vendor authorization; consumer/store remains gated; no arbitrary prompt, cross-APK Provider/Job, other model endpoint, or credential import yet"
