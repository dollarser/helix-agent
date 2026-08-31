#!/usr/bin/env bash
set -euo pipefail

readonly project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -z "${JAVA_HOME:-}" ]] && command -v brew >/dev/null 2>&1; then
    readonly homebrew_jdk="$(brew --prefix openjdk@17 2>/dev/null)/libexec/openjdk.jdk/Contents/Home"
    if [[ -x "$homebrew_jdk/bin/java" ]]; then
        export JAVA_HOME="$homebrew_jdk"
    fi
fi

readonly consumer_apk="$project_root/app/build/outputs/apk/consumer/debug/app-consumer-debug.apk"
readonly developer_apk="$project_root/app/build/outputs/apk/developer/debug/app-developer-debug.apk"
readonly proot_apk="$project_root/runtime/proot-app/build/outputs/apk/debug/proot-app-debug.apk"
readonly cli_apk="$project_root/runtime/cli-app/build/outputs/apk/debug/cli-app-debug.apk"
readonly sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
readonly apkanalyzer_bin="$sdk_root/cmdline-tools/latest/bin/apkanalyzer"

for required_file in "$consumer_apk" "$developer_apk" "$proot_apk" "$cli_apk" "$apkanalyzer_bin"; do
    if [[ ! -e "$required_file" ]]; then
        printf 'Missing required artifact: %s\n' "$required_file" >&2
        exit 1
    fi
done

assert_application_id() {
    local apk="$1"
    local expected="$2"
    local actual
    actual="$($apkanalyzer_bin manifest application-id "$apk")"
    if [[ "$actual" != "$expected" ]]; then
        printf 'Unexpected applicationId for %s: expected %s, got %s\n' "$apk" "$expected" "$actual" >&2
        exit 1
    fi
}

assert_application_id "$consumer_apk" "com.helix.agent"
assert_application_id "$developer_apk" "com.helix.agent.developer"
assert_application_id "$proot_apk" "com.helix.runtime.proot"
assert_application_id "$cli_apk" "com.helix.runtime.cli"

readonly developer_markers=(
    HELIX_DEVELOPER_ONLY_APP
    HELIX_DEVELOPER_ONLY_FILES_ALLFILES
    HELIX_DEVELOPER_ONLY_AUTOMATION
    HELIX_DEVELOPER_ONLY_ROOT
    HELIX_DEVELOPER_ONLY_PROOT_CLIENT
    HELIX_DEVELOPER_ONLY_CLI_CLIENT
)

consumer_strings="$(
    {
        unzip -p "$consumer_apk" 'classes*.dex' 2>/dev/null
        # resources.arsc is scanned symmetrically with the developer APK: a marker smuggled
        # into consumer resources (not just dex) must also fail this gate.
        unzip -p "$consumer_apk" resources.arsc 2>/dev/null
        "$apkanalyzer_bin" manifest print "$consumer_apk"
    } | strings
)"
developer_strings="$(
    {
        unzip -p "$developer_apk" 'classes*.dex' 2>/dev/null
        unzip -p "$developer_apk" resources.arsc 2>/dev/null
        "$apkanalyzer_bin" manifest print "$developer_apk"
    } | strings
)"

for marker in "${developer_markers[@]}"; do
    if grep -Fq "$marker" <<<"$consumer_strings"; then
        printf 'Consumer APK contains developer-only marker: %s\n' "$marker" >&2
        exit 1
    fi
    if ! grep -Fq "$marker" <<<"$developer_strings"; then
        printf 'Developer APK is missing marker: %s\n' "$marker" >&2
        exit 1
    fi
done

consumer_dependencies="$("$project_root/gradlew" -q :app:dependencies --configuration consumerDebugRuntimeClasspath)"
developer_dependencies="$("$project_root/gradlew" -q :app:dependencies --configuration developerDebugRuntimeClasspath)"

readonly developer_projects=(
    feature:files-allfiles
    tools:automation
    tools:root
    runtime:proot-client
    runtime:cli-client
)

for dependency in "${developer_projects[@]}"; do
    if grep -Fq "project :$dependency" <<<"$consumer_dependencies"; then
        printf 'Consumer dependency graph contains developer-only project: %s\n' "$dependency" >&2
        exit 1
    fi
    if ! grep -Fq "project :$dependency" <<<"$developer_dependencies"; then
        printf 'Developer dependency graph is missing project: %s\n' "$dependency" >&2
        exit 1
    fi
done

if "$apkanalyzer_bin" manifest permissions "$proot_apk" | grep -Fq 'android.permission.INTERNET'; then
    printf 'PRoot Runtime must not request INTERNET\n' >&2
    exit 1
fi

if ! "$apkanalyzer_bin" manifest permissions "$cli_apk" | grep -Fq 'android.permission.INTERNET'; then
    printf 'CLI Runtime must request INTERNET\n' >&2
    exit 1
fi

printf 'Variant boundary verification passed.\n'
