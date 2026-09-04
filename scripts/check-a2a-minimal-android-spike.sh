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
readonly spike_tmp="$(mktemp -d "${TMPDIR:-/tmp}/helix-a2a-minimal-r8.XXXXXX")"
cleanup() {
    if [[ "$(basename "$spike_tmp")" == helix-a2a-minimal-r8.* ]]; then
        rm -r -- "$spike_tmp"
    fi
}
trap cleanup EXIT

./gradlew :spikes:a2a-minimal:bundleLibRuntimeToJarDebug --no-configuration-cache >/dev/null

readonly agp_builder_jar="$(
    find "$gradle_module_root/com.android.tools.build/builder/$agp_version" \
        -type f -name "builder-$agp_version.jar" -print -quit
)"
if [[ -z "$agp_builder_jar" ]]; then
    printf 'AGP builder %s was not found in the Gradle cache.\n' "$agp_version" >&2
    exit 1
fi

program_jars=(
    "$project_root/spikes/a2a-minimal/build/intermediates/runtime_library_classes_jar/debug/bundleLibRuntimeToJarDebug/classes.jar"
)
mkdir -p "$spike_tmp/aars"
while IFS=: read -r module_group module_name module_version; do
    module_dir="$gradle_module_root/$module_group/$module_name/$module_version"
    if [[ ! -d "$module_dir" ]]; then
        continue
    fi
    module_jar="$(find "$module_dir" -type f -name "$module_name-$module_version.jar" -print -quit)"
    if [[ -n "$module_jar" ]]; then
        program_jars+=("$module_jar")
        continue
    fi
    module_aar="$(find "$module_dir" -type f -name '*.aar' -print -quit)"
    if [[ -n "$module_aar" ]] && unzip -l "$module_aar" classes.jar >/dev/null 2>&1; then
        extracted_jar="$spike_tmp/aars/${module_group//./_}-$module_name-$module_version.jar"
        unzip -p "$module_aar" classes.jar > "$extracted_jar"
        program_jars+=("$extracted_jar")
    fi
done < <(awk -F= '$2 ~ /(^|,)debugRuntimeClasspath(,|$)/ {print $1}' spikes/a2a-minimal/gradle.lockfile)

mkdir "$spike_tmp/r8"
java -cp "$agp_builder_jar" com.android.tools.r8.R8 \
    --release \
    --min-api 29 \
    --lib "$android_jar" \
    --pg-conf "$project_root/spikes/a2a-minimal/r8-spike.pro" \
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
printf 'A2A minimal Android bytecode spike passed: minApi=29 programJars=%s dexBytes=%s methodIds=%s classDefs=%s\n' \
    "${#program_jars[@]}" "$dex_bytes" "$method_ids" "$class_defs"
