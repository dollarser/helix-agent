#!/usr/bin/env bash
# Stage closeout only: no device, real-provider, Git mutation, or new feature work.
set -u
out=build/phase-closeout-2026-10-01
mkdir -p "$out"
sources=(app core provider runtime extensions tools feature prompts testing gradle config evals
  build.gradle.kts settings.gradle.kts gradle.properties .editorconfig AGENTS.md
  scripts/agent_eval.py scripts/run-agent-eval.py scripts/with-host-slot.py scripts/check-all.sh)
python3 scripts/run-agent-eval.py provenance --source "${sources[@]}" --output "$out/source-before.json" || exit "$?"
python3 scripts/with-host-slot.py -- ./gradlew test detekt spotlessCheck \
  :app:lintConsumerDebug :app:lintDeveloperDebug \
  :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest \
  --max-workers=2 --console=plain --continue --no-daemon --no-parallel > "$out/host.log" 2>&1
result=$?
printf '%s\n' "$result" > "$out/host.exit"
tail -n 20 "$out/host.log"
if [ "$result" -ne 0 ]; then exit "$result"; fi
python3 scripts/run-agent-eval.py provenance --source "${sources[@]}" --output "$out/source-after.json" || exit "$?"
cmp "$out/source-before.json" "$out/source-after.json" || exit "$?"
printf 'Source manifest unchanged across host validation.\n'
