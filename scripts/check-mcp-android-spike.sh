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
readonly dexdump="$android_sdk/build-tools/36.0.0/dexdump"
if [[ ! -f "$android_jar" || ! -x "$dexdump" ]]; then
    printf 'Android platform 36 and Build Tools 36.0.0 are required.\n' >&2
    exit 1
fi

readonly agp_version="$(awk -F '"' '$1 ~ /^agp =/ {print $2}' gradle/libs.versions.toml)"
readonly gradle_user_root="${GRADLE_USER_HOME:-${HOME}/.gradle}"
readonly gradle_module_root="$gradle_user_root/caches/modules-2/files-2.1"

./gradlew :extensions:mcp:jar :extensions:mcp:dependencies --no-configuration-cache >/dev/null

readonly agp_builder_jar="$(
    find "$gradle_module_root/com.android.tools.build/builder/$agp_version" \
        -type f -name "builder-$agp_version.jar" -print -quit
)"
if [[ -z "$agp_builder_jar" ]]; then
    printf 'AGP builder %s was not found in the Gradle cache.\n' "$agp_version" >&2
    exit 1
fi

program_jars=(
    "$project_root/extensions/mcp/build/libs/mcp-0.1.0-SNAPSHOT.jar"
    "$project_root/core/model/build/libs/model-0.1.0-SNAPSHOT.jar"
    "$project_root/core/policy/build/libs/policy-0.1.0-SNAPSHOT.jar"
    "$project_root/tools/framework/build/libs/framework-0.1.0-SNAPSHOT.jar"
)
for project_jar in "${program_jars[@]}"; do
    if [[ ! -f "$project_jar" ]]; then
        printf 'Required project JAR was not built: %s\n' "$project_jar" >&2
        exit 1
    fi
done
while IFS=: read -r module_group module_name module_version; do
    module_dir="$gradle_module_root/$module_group/$module_name/$module_version"
    if [[ ! -d "$module_dir" ]]; then
        continue
    fi
    module_jar="$(find "$module_dir" -type f -name "$module_name-$module_version.jar" -print -quit)"
    if [[ -n "$module_jar" ]]; then
        program_jars+=("$module_jar")
    fi
done < <(awk -F= '$2 ~ /(^|,)runtimeClasspath(,|$)/ {print $1}' extensions/mcp/gradle.lockfile)

readonly spike_tmp="$(mktemp -d "${TMPDIR:-/tmp}/helix-mcp-r8.XXXXXX")"
cleanup() {
    if [[ "$(basename "$spike_tmp")" == helix-mcp-r8.* ]]; then
        rm -r -- "$spike_tmp"
    fi
}
trap cleanup EXIT

mkdir "$spike_tmp/r8"
java -cp "$agp_builder_jar" com.android.tools.r8.R8 \
    --release \
    --min-api 29 \
    --lib "$android_jar" \
    --pg-conf "$project_root/extensions/mcp/r8-spike.pro" \
    --output "$spike_tmp/r8" \
    "${program_jars[@]}"

readonly dex_file="$spike_tmp/r8/classes.dex"
if [[ ! -f "$dex_file" ]]; then
    printf 'R8 did not produce classes.dex.\n' >&2
    exit 1
fi

readonly dex_bytes="$(stat -f '%z' "$dex_file" 2>/dev/null || stat -c '%s' "$dex_file")"
readonly dex_header="$($dexdump -f "$dex_file")"
readonly method_ids="$(awk '$1 == "method_ids_size" {print $3}' <<<"$dex_header")"
readonly class_defs="$(awk '$1 == "class_defs_size" {print $3}' <<<"$dex_header")"

java -cp "$agp_builder_jar" com.android.tools.r8.R8 --version
printf 'MCP Android bytecode spike passed: minApi=29 programJars=%s dexBytes=%s methodIds=%s classDefs=%s\n' \
    "${#program_jars[@]}" "$dex_bytes" "$method_ids" "$class_defs"
