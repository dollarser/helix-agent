#!/usr/bin/env bash
set -euo pipefail

readonly project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"

if [[ -z "${JAVA_HOME:-}" && -x /opt/homebrew/opt/openjdk@17/bin/java ]]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
fi

readonly android_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$android_sdk" ]]; then
    printf 'ANDROID_SDK_ROOT or ANDROID_HOME is required.\n' >&2
    exit 1
fi

readonly android_jar="$android_sdk/platforms/android-36/android.jar"
if [[ ! -f "$android_jar" ]]; then
    printf 'Android platform 36 is required.\n' >&2
    exit 1
fi

readonly agp_version="$(awk -F '"' '$1 ~ /^agp =/ {print $2}' gradle/libs.versions.toml)"
readonly gradle_user_root="${GRADLE_USER_HOME:-${HOME}/.gradle}"
readonly gradle_module_root="$gradle_user_root/caches/modules-2/files-2.1"

./gradlew :spikes:a2a-sdk:bundleLibRuntimeToJarDebug --no-configuration-cache >/dev/null

readonly agp_builder_jar="$(
    find "$gradle_module_root/com.android.tools.build/builder/$agp_version" \
        -type f -name "builder-$agp_version.jar" -print -quit
)"
if [[ -z "$agp_builder_jar" ]]; then
    printf 'AGP builder %s was not found in the Gradle cache.\n' "$agp_version" >&2
    exit 1
fi

program_jars=(
    "$project_root/spikes/a2a-sdk/build/intermediates/runtime_library_classes_jar/debug/bundleLibRuntimeToJarDebug/classes.jar"
)
while IFS=: read -r module_group module_name module_version; do
    module_dir="$gradle_module_root/$module_group/$module_name/$module_version"
    if [[ ! -d "$module_dir" ]]; then
        continue
    fi
    module_jar="$(find "$module_dir" -type f -name "$module_name-$module_version.jar" -print -quit)"
    if [[ -n "$module_jar" ]]; then
        program_jars+=("$module_jar")
    fi
done < <(awk -F= '$2 ~ /(^|,)debugRuntimeClasspath(,|$)/ {print $1}' spikes/a2a-sdk/gradle.lockfile)

readonly spike_tmp="$(mktemp -d "${TMPDIR:-/tmp}/helix-a2a-sdk-r8.XXXXXX")"
cleanup() {
    if [[ "$(basename "$spike_tmp")" == helix-a2a-sdk-r8.* ]]; then
        rm -r -- "$spike_tmp"
    fi
}
trap cleanup EXIT

mkdir "$spike_tmp/r8"
readonly r8_log="$spike_tmp/r8.log"
set +e
java -cp "$agp_builder_jar" com.android.tools.r8.R8 \
    --release \
    --min-api 29 \
    --lib "$android_jar" \
    --pg-conf "$project_root/spikes/a2a-sdk/r8-spike.pro" \
    --output "$spike_tmp/r8" \
    "${program_jars[@]}" >"$r8_log" 2>&1
readonly r8_status=$?
set -e

if [[ $r8_status -ne 0 ]] && grep -q 'Missing class java.net.http.HttpClient' "$r8_log"; then
    java -cp "$agp_builder_jar" com.android.tools.r8.R8 --version
    printf 'A2A SDK Android bytecode spike rejected as expected: minApi=29 programJars=%s reason=missing-java.net.http.HttpClient\n' \
        "${#program_jars[@]}"
    exit 0
fi

if [[ $r8_status -ne 0 ]]; then
    cat "$r8_log" >&2
    printf 'A2A SDK Android bytecode spike failed for an unexpected reason.\n' >&2
    exit "$r8_status"
fi

printf 'Pinned official SDK unexpectedly passed strict Android R8; review the HXA-077 decision evidence.\n' >&2
exit 1
