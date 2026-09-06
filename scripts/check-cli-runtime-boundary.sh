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

copilot="$repo_root/runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/CopilotSubscriptionModel.kt"
test "$(rg -F 'https://api.githubcopilot.com/chat/completions' "$copilot" | wc -l | tr -d ' ')" = 1
rg -F 'CliSubscriptionProvider.COPILOT' "$copilot" >/dev/null
claude="$repo_root/runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/ClaudeSubscriptionModel.kt"
test "$(rg -F 'https://api.anthropic.com/v1/messages?beta=true' "$claude" | wc -l | tr -d ' ')" = 1
rg -F 'CliSubscriptionProvider.CLAUDE' "$claude" >/dev/null
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
protocol="$repo_root/runtime/cli-client/src/main/kotlin/com/helix/runtime/cli/client/CliRuntimeProtocol.kt"
test "$(rg -c 'TRANSACTION_JOB_(SUBMIT|QUERY|CANCEL|RECONCILE)' "$protocol")" = 4
client="$repo_root/runtime/cli-client/src/main/kotlin/com/helix/runtime/cli/client/CliModelJobClient.kt"
payload_codec="$repo_root/runtime/cli-client/src/main/kotlin/com/helix/runtime/cli/client/CliModelPayloadCodec.kt"
rg -F 'submitAndAwaitFixed' "$client" >/dev/null
rg -F 'submitAndAwait(' "$client" >/dev/null
rg -F 'TRANSACTION_JOB_QUERY' "$client" >/dev/null
rg -F 'TRANSACTION_JOB_RECONCILE' "$client" >/dev/null
rg -F 'const val MAX_BYTES = 512 * 1024' "$payload_codec" >/dev/null
rg -F 'const val MAX_BYTES = 1024 * 1024' "$payload_codec" >/dev/null
rg -F 'const val MAX_EVENTS = 2048' "$payload_codec" >/dev/null
rg -F 'CliPfdChannel' "$client" >/dev/null
rg -F 'reconciledAtEpochMillis' "$repo_root/runtime/cli-client/src/main/kotlin/com/helix/runtime/cli/client/CliModelJobRecord.kt" >/dev/null
rg -F 'put("store", false)' "$repo_root/runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/CodexSubscriptionModel.kt" >/dev/null
if rg -n 'accessToken|refreshToken|accountId|authorization' "$client" "$payload_codec"; then
    echo "credential material escaped into the main-app model IPC" >&2
    exit 1
fi
supervisor="$repo_root/runtime/cli-client/src/main/kotlin/com/helix/runtime/cli/client/CliRuntimeSupervisor.kt"
rg -F 'ComponentName(CliRuntimeProtocol.RUNTIME_PACKAGE, CliRuntimeProtocol.SERVICE_CLASS)' "$supervisor" >/dev/null
rg -F 'fun visibleUiCause(): CliRuntimeVerification.Cause? = localCause(checkStopped = false)' "$supervisor" >/dev/null
rg -F 'Context.BIND_AUTO_CREATE' "$supervisor" >/dev/null
rg -F 'context.unbindService(connection)' "$supervisor" >/dev/null
rg -F 'ApplicationInfo.FLAG_STOPPED' "$supervisor" >/dev/null
app_manifest="$repo_root/app/src/developer/AndroidManifest.xml"
rg -F '<package android:name="com.helix.runtime.cli" />' "$app_manifest" >/dev/null
rg -F '<uses-permission android:name="com.helix.permission.BIND_CLI_RUNTIME" />' "$app_manifest" >/dev/null

developer_provider="$repo_root/app/src/developer/kotlin/com/helix/app/provider/CodexSubscriptionProvider.kt"
developer_module="$repo_root/app/src/developer/kotlin/com/helix/app/provider/SubscriptionProviderModule.kt"
consumer_module="$repo_root/app/src/consumer/kotlin/com/helix/app/provider/SubscriptionProviderModule.kt"
rg -F 'CodexSubscriptionProvider(context, config)' "$developer_module" >/dev/null
rg -F 'ComponentName(CliRuntimeProtocol.RUNTIME_PACKAGE, when (providerId)' "$developer_module" >/dev/null
rg -F 'CODEX_ID -> CliRuntimeProtocol.CODEX_LOGIN_ACTIVITY' "$developer_module" >/dev/null
rg -F 'CLAUDE_ID -> "com.helix.runtime.cli.app.ClaudeLoginActivity"' "$developer_module" >/dev/null
rg -F 'toolCalls = false' "$developer_module" >/dev/null
rg -F 'vision = false' "$developer_module" >/dev/null
rg -F 'CliModelJobClient' "$developer_provider" >/dev/null
rg -F 'fun create(context: Context, config: ProviderConfig): ModelProvider? = null' "$consumer_module" >/dev/null
rg -F 'ManagedProviderAccountResult.NOT_SUPPORTED' "$consumer_module" >/dev/null
consumer_apk="$repo_root/app/build/outputs/apk/consumer/debug/app-consumer-debug.apk"
test -f "$consumer_apk"
if unzip -p "$consumer_apk" 'classes*.dex' | strings | rg 'CodexSubscriptionProvider|runtime/cli/client|subscription-(codex|claude|grok|copilot)'; then
    echo "consumer APK contains subscription implementation" >&2
    exit 1
fi

echo "Subscription managed account entry, Runtime boundary, consumer exclusion, and normal chat path checks passed"
