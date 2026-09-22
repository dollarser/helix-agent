"""HXA-129 local acceptance. Run under with-host-slot; never attach to an existing emulator."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

parser = argparse.ArgumentParser()
parser.add_argument('--output', required=True)
args = parser.parse_args()
output = Path(args.output)
output.mkdir(parents=True, exist_ok=False)
classes = ','.join([
    'com.helix.app.connector.ConnectorLifecycleDeviceTest',
    'com.helix.app.connector.ConnectorSnapshotReferenceDeviceTest',
    'com.helix.app.connector.ConnectorCatalogMigrationDeviceTest',
    'com.helix.app.connector.ConnectorSendBoundaryDeviceTest',
    'com.helix.app.connector.ConnectorDispatchDeviceTest',
    'com.helix.app.connector.ConnectorSessionPanelDeviceTest',
    'com.helix.app.connector.ConnectorDeviceTest',
    'com.helix.app.ConnectorInstallationDeviceTest',
    'com.helix.app.marketplace.MarketplaceDeviceTest',
    'com.helix.app.SkillInstallationDeviceTest',
    'com.helix.app.SkillToolsDeviceTest',
    'com.helix.app.McpToolDiscoveryDeviceTest',
    'com.helix.app.SessionPermissionDeviceTest',
    'com.helix.app.chat.SessionForkDeviceTest',
    'com.helix.app.connector.ConnectorUiDeviceTest',
])
summary = []
port = 5660
for api in (29, 36):
    for flavor in ('consumer', 'developer'):
        package = 'com.helix.agent' + ('.developer' if flavor == 'developer' else '')
        runner = package + '.test/com.helix.app.HelixAndroidJUnitRunner'
        batch = f'api{api}-{flavor}'
        command = [sys.executable, 'scripts/run-owned-emulator.py',
                   '--avd', f'Helix191_API{api}', '--port', str(port),
                   '--apk', f'app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk',
                   '--test-apk', f'app/build/outputs/apk/androidTest/{flavor}/debug/app-{flavor}-debug-androidTest.apk',
                   '--runner', runner, '--classes', classes,
                   '--after-script', 'scripts/connector-install-recovery-followup.py',
                   '--output', str(output / batch), '--timeout', '900']
        with (output / f'{batch}.log').open('w') as log:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT,
                                    env={**os.environ, 'HXA129_RUNNER': runner}, check=False)
        summary.append({'batch': batch, 'exitCode': result.returncode})
        (output / 'summary.json').write_text(json.dumps(summary, indent=2))
        print(batch, 'exit', result.returncode, flush=True)
        port += 2
        if result.returncode:
            raise SystemExit(result.returncode)
    apk = 'core/storage/build/outputs/apk/androidTest/debug/storage-debug-androidTest.apk'
    batch = f'api{api}-storage'
    with (output / f'{batch}.log').open('w') as log:
        result = subprocess.run([sys.executable, 'scripts/run-owned-emulator.py',
                                 '--avd', f'Helix191_API{api}', '--port', str(port),
                                 '--apk', apk, '--test-apk', apk,
                                 '--runner', 'com.helix.core.storage.test/androidx.test.runner.AndroidJUnitRunner',
                                 '--classes', 'com.helix.core.storage.ConnectorMigrationDeviceTest,com.helix.core.storage.RoomMigrationFixtureTest',
                                 '--output', str(output / batch), '--timeout', '600'],
                                stdout=log, stderr=subprocess.STDOUT, check=False)
    summary.append({'batch': batch, 'exitCode': result.returncode})
    (output / 'summary.json').write_text(json.dumps(summary, indent=2))
    print(batch, 'exit', result.returncode, flush=True)
    port += 2
    if result.returncode:
        raise SystemExit(result.returncode)
print('PASS all connector quadrants, process-death boundaries and storage migrations', flush=True)
