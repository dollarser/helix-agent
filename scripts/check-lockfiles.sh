#!/usr/bin/env bash
set -euo pipefail
export ORG_GRADLE_PROJECT_includeSpikes=true

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
    python3 "$project_root/scripts/gradle-projects.py" --snapshot
}

before="$(lock_snapshot)"
readonly before
readonly lock_count="$(printf '%s\n' "$before" | wc -l | tr -d '[:space:]')"
project_list="$(python3 "$project_root/scripts/gradle-projects.py")"
tasks=(dependencies)
while IFS= read -r project_path; do
    tasks+=("${project_path}:dependencies")
done <<< "$project_list"

"$project_root/gradlew" "${tasks[@]}" --write-locks >/dev/null

after="$(lock_snapshot)"
readonly after
if [[ "$before" != "$after" ]]; then
    printf 'Dependency locks changed after resolution. Review and commit the lock diff.\n' >&2
    diff <(printf '%s\n' "$before") <(printf '%s\n' "$after") || true
    exit 1
fi

printf 'Dependency lock verification passed (%s files).\n' "$lock_count"
