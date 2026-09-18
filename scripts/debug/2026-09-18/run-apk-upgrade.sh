#!/usr/bin/env bash
# Arguments: old worktree, dedicated AVD, unused port, new evidence directory.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$repo_root"
old_root="${1:?old worktree}"
avd="${2:?owned AVD}"
port="${3:?unused even port}"
out="${4:?new evidence directory}"
export HELIX_UPGRADE_NEW_APK="$repo_root/app/build/outputs/apk/developer/debug/app-developer-debug.apk"
export HELIX_UPGRADE_NEW_TEST_APK="$repo_root/app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk"
python3 scripts/debug/2026-09-09/run-owned-emulator.py \
    --avd "$avd" --port "$port" --output "$out" \
    --apk "$old_root/app/build/outputs/apk/developer/debug/app-developer-debug.apk" \
    --test-apk "$old_root/app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk" \
    --runner com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner \
    --classes com.helix.app.proot.ApkReplacementUpgradeDeviceTest \
    --instrument-arg upgradePhase=seed \
    --after-script scripts/debug/2026-09-18/apk-upgrade-after.py --timeout 600
