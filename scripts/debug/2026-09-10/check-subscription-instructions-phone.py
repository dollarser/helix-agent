"""One opt-in synthetic protocol comparison; no user history or local tool execution."""
import os
from pathlib import Path
import subprocess

serial = os.environ['HELIX_PHONE_SERIAL']
assert not serial.startswith('emulator-')
adb = str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb')
def run(*args, timeout=120):
    return subprocess.check_output([adb, '-s', serial, *args], text=True, timeout=timeout)

assert run('get-state').strip() == 'device'
out = Path('build/debug/2026-09-10/subscription-instructions')
for apk in ['runtime/cli-app/build/outputs/apk/debug/cli-app-debug.apk',
            'runtime/cli-app/build/outputs/apk/androidTest/debug/cli-app-debug-androidTest.apk',
            'app/build/outputs/apk/developer/debug/app-developer-debug.apk']:
    assert 'Success' in run('install', '-r', apk)
try:
    result = run('shell', 'am', 'instrument', '-w', '-r', '-e', 'realCodex', 'true', '-e', 'class',
                 'com.helix.runtime.cli.app.CodexRealAccountDiagnosticTest#systemInstructionCompatibility',
                 'com.helix.runtime.cli.test/androidx.test.runner.AndroidJUnitRunner', timeout=180)
    (out / 'phone.txt').write_text(result)
    print(result)
    assert 'OK (1 test)' in result and 'FAILURES' not in result
finally:
    run('uninstall', 'com.helix.runtime.cli.test')
    run('shell', 'am', 'start', '-W', '-n', 'com.helix.agent.developer/com.helix.app.MainActivity')
