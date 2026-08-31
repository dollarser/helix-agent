#!/usr/bin/env bash
set -euo pipefail

readonly project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"

if [[ -z "${JAVA_HOME:-}" && -x /opt/homebrew/opt/openjdk@17/bin/java ]]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
fi

# Wildcard versions in any Maven position: a run of version characters ending in `+`
# (covers bare "+", "1.+", "1.2.3+"), latest.release/integration, and SNAPSHOT.
if rg --line-number '(^|[=[:space:]"])[A-Za-z0-9.+-]*\+|latest\.(release|integration)|SNAPSHOT' gradle/libs.versions.toml; then
    printf 'Dynamic or snapshot version found in the version catalog.\n' >&2
    exit 1
fi

lock_snapshot() {
    # sha256sum (Linux/CI) or shasum (macOS); both produce "<hash>  <file>" lines.
    find . -name gradle.lockfile -not -path '*/build/*' -print0 |
        sort -z |
        if command -v sha256sum >/dev/null 2>&1; then
            xargs -0 sha256sum
        else
            xargs -0 shasum -a 256
        fi
}

readonly before="$(lock_snapshot)"
readonly lock_count="$(find . -name gradle.lockfile -not -path '*/build/*' | wc -l | tr -d '[:space:]')"

if [[ "$lock_count" != "29" ]]; then
    printf 'Expected 29 dependency lock files, found %s.\n' "$lock_count" >&2
    exit 1
fi

readonly projects=(
    app core:model core:agent core:policy core:storage core:workspace
    provider:api provider:openai-responses provider:openai-chat provider:anthropic provider:catalog
    extensions:mcp extensions:skills feature:browser feature:files feature:files-allfiles
    runtime:quickjs runtime:proot-client runtime:proot-app runtime:cli-client runtime:cli-app
    tools:framework tools:android tools:automation tools:browser tools:files tools:root testing
)

tasks=(dependencies)
for project_path in "${projects[@]}"; do
    tasks+=(":${project_path}:dependencies")
done

"$project_root/gradlew" "${tasks[@]}" --write-locks >/dev/null

readonly after="$(lock_snapshot)"
if [[ "$before" != "$after" ]]; then
    printf 'Dependency locks changed after resolution. Review and commit the lock diff.\n' >&2
    diff <(printf '%s\n' "$before") <(printf '%s\n' "$after") || true
    exit 1
fi

printf 'Dependency lock verification passed (%s files).\n' "$lock_count"
