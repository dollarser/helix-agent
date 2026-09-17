#!/usr/bin/env bash
# Repeat only the formerly timing-dependent test, forcing each Test task to execute.
set -euo pipefail
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$project_root"
mkdir -p build/ci-investigation/scheduler-order
for attempt in {1..10}; do
    ./gradlew :tools:framework:test --rerun \
        --tests 'com.helix.tools.framework.ToolSchedulerTest.resultsComeBackInCallOrderEvenWhenCompletionIsOutOfOrder' \
        --console=plain > "build/ci-investigation/scheduler-order/repeat-$attempt.log" 2>&1
done
