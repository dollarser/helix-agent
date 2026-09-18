#!/usr/bin/env bash
set -euo pipefail
variant="${1:?consumer or developer}"
api="${2:?API}"
port="${3:?exclusive even port}"
output="${4:?new evidence directory}"
suffix=""
if [[ "$variant" == developer ]]; then suffix=.developer; fi
python3 scripts/debug/2026-09-09/run-owned-emulator.py \
  --avd "HelixApkUpgrade_API${api}_20260918" --port "$port" --memory-mb 4096 --cores 4 \
  --output "$output" \
  --apk "app/build/outputs/apk/$variant/debug/app-$variant-debug.apk" \
  --test-apk "app/build/outputs/apk/androidTest/$variant/debug/app-$variant-debug-androidTest.apk" \
  --runner "com.helix.agent${suffix}.test/com.helix.app.HelixAndroidJUnitRunner" \
  --classes com.helix.app.PermissionAtomicityDeviceTest,com.helix.app.ProductionMigrationDeviceTest,com.helix.app.SessionPermissionDeviceTest,com.helix.app.SessionPermissionRecoveryDeviceTest,com.helix.app.BrowserActivityLifecycleDeviceTest,com.helix.app.ui.FilesImportExportUiTest#removingTheCurrentSafLocationReturnsToWorkspaceWithoutStaleActions \
  --recovery-setup-class com.helix.app.SessionPermissionRecoveryDeviceTest --instrument-arg recoveryPhase=verify \
  --timeout 600
