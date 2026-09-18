#!/usr/bin/env bash
# Dedicated AVDs, sequential fresh owned processes; no shared installation or serial.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$repo_root"
if [[ $# -eq 0 ]]; then set -- consumer:29 developer:29 consumer:36 developer:36; fi
port="${INTEGRATION_PORT_BASE:-5662}"
for quadrant in "$@"; do
        variant="${quadrant%%:*}"
        api="${quadrant##*:}"
        [[ "$variant" == consumer || "$variant" == developer ]]
        [[ "$api" == 29 || "$api" == 36 ]]
        suffix=""
        if [[ "$variant" == developer ]]; then suffix=".developer"; fi
        python3 scripts/debug/2026-09-09/run-owned-emulator.py \
            --avd "HelixIntegration191_API${api}_20260918" --port "$port" \
            --apk "app/build/outputs/apk/${variant}/debug/app-${variant}-debug.apk" \
            --test-apk "app/build/outputs/apk/androidTest/${variant}/debug/app-${variant}-debug-androidTest.apk" \
            --runner "com.helix.agent${suffix}.test/com.helix.app.HelixAndroidJUnitRunner" \
            --classes com.helix.app.ui.SessionSearchDeviceTest \
            --recovery-setup-class com.helix.app.ui.SessionSearchDeviceTest \
            --instrument-arg recoveryPhase=verify \
            --after-script scripts/debug/2026-09-18/run-191-storage-connected.py \
            --output "build/integration-193-191/${variant}-${api}"
        port=$((port + 2))
done
