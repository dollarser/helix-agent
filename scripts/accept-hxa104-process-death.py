#!/usr/bin/env python3
"""Exercise debug-only crash/ANR activity and verify production diagnostics in a new process."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time
import re
import xml.etree.ElementTree as ET


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('serial')
    p.add_argument('--phase', choices=['crash', 'anr'], required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    if not a.serial.startswith('emulator-'):
        p.error('this runner is only authorized for an explicitly selected emulator')
    a.output.mkdir(parents=True, exist_ok=False)
    adb = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))) / 'platform-tools/adb'
    prefix = [str(adb), '-s', a.serial]
    def shell(*args, timeout=30):
        return subprocess.check_output(prefix + ['shell', *args], text=True, stderr=subprocess.STDOUT, timeout=timeout)
    since = str(int(shell('date', '+%s').strip()) * 1000)
    shell('am', 'force-stop', 'com.helix.agent')
    with (a.output / 'logcat.log').open('w') as output:
        logcat = subprocess.Popen(prefix + ['logcat', '-T', '1', '-v', 'threadtime'], stdout=output, stderr=subprocess.STDOUT)
        try:
            (a.output / 'launch.log').write_text(shell('am', 'start', '-W', '-n', 'com.helix.agent/com.helix.app.diagnostics.DiagnosticFailureActivity', '--es', 'phase', a.phase))
            pid = shell('pidof', 'com.helix.agent').strip()
            time.sleep(6)
            if a.phase == 'anr':
                try:
                    shell('input', 'tap', '200', '200', timeout=12)
                except subprocess.TimeoutExpired:
                    pass  # Input dispatch can block; only the system ANR below counts as evidence.
                deadline = time.monotonic() + 60
                while time.monotonic() < deadline:
                    text = (a.output / 'logcat.log').read_text(errors='replace')
                    if 'ANR in com.helix.agent' in text:
                        break
                    time.sleep(2)
                else:
                    raise RuntimeError('no system ANR observed; cannot count an artificial timeout as ANR')
                (a.output / 'anr.txt').write_text(shell('dumpsys', 'activity', 'lastanr'))
                shell('uiautomator', 'dump', '/sdcard/hxa104-window.xml')
                xml = shell('cat', '/sdcard/hxa104-window.xml')
                (a.output / 'anr-dialog.xml').write_text(xml)
                root = ET.fromstring(xml)
                assert any('Helix' in node.get('text', '') for node in root.iter('node')), 'wrong app ANR dialog'
                close = next(node for node in root.iter('node') if node.get('resource-id') == 'android:id/aerr_close')
                x1, y1, x2, y2 = map(int, re.findall(r'\d+', close.attrib['bounds']))
                shell('input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
                time.sleep(2)
                shell('input', 'keyevent', 'KEYCODE_TAB', 'KEYCODE_ENTER')
                deadline = time.monotonic() + 20
                while time.monotonic() < deadline:
                    current = subprocess.run(prefix + ['shell', 'pidof', 'com.helix.agent'], capture_output=True, text=True)
                    if pid not in current.stdout.split():
                        break
                    time.sleep(1)
                else:
                    raise RuntimeError('ANR dialog did not terminate the affected PID')
            time.sleep(2)
            (a.output / 'exit-info.txt').write_text(shell('dumpsys', 'activity', 'exit-info', 'com.helix.agent'))
            result = shell('am', 'instrument', '-w', '-r', '-e', 'class', 'com.helix.app.diagnostics.ProcessDeathEvidenceDeviceTest#verifyPreviousProcessEvidence', '-e', 'helix.diagnostics.phase', 'verify-' + a.phase, '-e', 'helix.diagnostics.since', since, 'com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner', timeout=90)
            (a.output / 'verification.log').write_text(result)
            subprocess.run(prefix + ['pull', '/sdcard/Android/data/com.helix.agent/files/hxa104-preview.json', str(a.output / 'preview.json')], check=True, stdout=subprocess.DEVNULL)
            passed = 'OK (1 test)' in result and 'INSTRUMENTATION_STATUS_CODE: 0' in result and 'INSTRUMENTATION_STATUS_CODE: -4' not in result
            (a.output / 'result.json').write_text(json.dumps({'phase': a.phase, 'pid': pid, 'since': since, 'passed': passed}, indent=2))
            if not passed:
                raise RuntimeError('new-process diagnostic verification failed; see verification.log')
        finally:
            logcat.terminate()
            logcat.wait(timeout=15)
            shell('am', 'force-stop', 'com.helix.agent')
    print(a.phase + ' verified on ' + a.serial)


if __name__ == '__main__':
    main()
