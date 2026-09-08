#!/usr/bin/env bash
set -euo pipefail
creator_serial="${1:?Usage: bash scripts/accept-hxa-149-skill-installer.sh <dedicated-serial>}"
creator_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
creator_adb="${ANDROID_HOME:?Set ANDROID_HOME}/platform-tools/adb"
creator_out="$creator_root/build/skill-authoring"
mkdir -p "$creator_out"
"$creator_adb" -s "$creator_serial" install -r "$creator_root/app/build/outputs/apk/consumer/debug/app-consumer-debug.apk"
"$creator_adb" -s "$creator_serial" install -r "$creator_root/app/build/outputs/apk/androidTest/consumer/debug/app-consumer-debug-androidTest.apk"
creator_log="$creator_out/$creator_serial-installer-native.log"
"$creator_adb" -s "$creator_serial" shell am instrument -w -r \
  -e class com.helix.app.SkillInstallationDeviceTest \
  com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner | tee "$creator_log"
rg '^OK \(1 test\)' "$creator_log"
! rg 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -[234]' "$creator_log"
creator_log="$creator_out/$creator_serial-installer-ui.log"
"$creator_adb" -s "$creator_serial" shell am instrument -w -r \
  -e class com.helix.app.ui.SkillInstallationUiTest \
  com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner | tee "$creator_log"
rg '^OK \(2 tests\)' "$creator_log"
! rg 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -[234]' "$creator_log"
if [[ -n "${HELIX_SKILL_MODEL:-}" ]]; then
  creator_log="$creator_out/$creator_serial-installer-model.log"
  "$creator_adb" -s "$creator_serial" shell am instrument -w -r \
    -e class com.helix.app.SkillInstallerModelDeviceTest -e skillInstallerModel true -e model "$HELIX_SKILL_MODEL" \
    com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner | tee "$creator_log"
  rg '^OK \(1 test\)' "$creator_log"
  ! rg 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -[234]' "$creator_log"
fi
