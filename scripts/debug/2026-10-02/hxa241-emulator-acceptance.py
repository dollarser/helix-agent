#!/usr/bin/env python3
"""Owner-authorized HXA-241 emulator checks. Reuses the existing isolated runner and JUnit parser."""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import os
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'build/hxa241/emulator-20261002'
SDK = Path(os.environ.get('ANDROID_HOME', Path.home() / 'Library/Android/sdk'))
ADB = SDK / 'platform-tools/adb'
SERIAL = 'emulator-5570'
CLASSES = [
    'MainActivityTest', 'ui.IaAuthorityDeviceTest', 'provider.ProviderModelDiscoveryUiTest',
    'chat.AttachmentE2eDeviceTest', 'chat.SessionInputProtocolDeviceTest',
    'chat.GoalModelCancellationDeviceTest', 'chat.EvaluationTrajectoryDeviceTest',
    'chat.SessionInputAdmissionFailureDeviceTest', 'localmodel.LocalProviderLoopDeviceTest',
    'CommandExecutionDetailsDeviceTest', 'TaskJourneyDeviceTest', 'ui.ProviderContextDeviceTest',
    'chat.SessionInputQueueDeviceTest', 'ui.ChatStopProgressDeviceTest', 'ui.ProviderFlowTest',
    'ui.ConversationHeaderDeviceTest', 'ui.MessageCopyDeviceTest', 'ui.SessionSettingsDeviceTest',
]


def run(args, *, timeout=180, log=None):
    if log is None:
        return subprocess.check_output([str(a) for a in args], cwd=ROOT, text=True, timeout=timeout)
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open('w') as stream:
        subprocess.run([str(a) for a in args], cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT,
                       timeout=timeout, check=True)


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def device_ready():
    if run([ADB, '-s', SERIAL, 'shell', 'getprop', 'ro.kernel.qemu']).strip() != '1':
        raise RuntimeError('Refuse to operate on a non-emulator')
    if run([ADB, '-s', SERIAL, 'shell', 'getprop', 'sys.boot_completed']).strip() != '1':
        raise RuntimeError('Emulator boot is incomplete')


def prepare():
    OUT.mkdir(parents=True, exist_ok=True)
    run(['python3', 'scripts/with-host-slot.py', '--', './gradlew',
         ':app:assembleConsumerDebug', ':app:assembleDeveloperDebug',
         ':app:assembleConsumerDebugAndroidTest', ':app:assembleDeveloperDebugAndroidTest',
         '--console=plain', '--no-daemon', '--no-parallel', '--max-workers=2'],
        timeout=1800, log=OUT/'build.log')
    run([ADB, '-s', SERIAL, 'wait-for-device'])
    for _ in range(120):
        if run([ADB, '-s', SERIAL, 'shell', 'getprop', 'sys.boot_completed']).strip() == '1':
            break
        time.sleep(1)
    device_ready()
    packages = {}
    for flavor in ('consumer', 'developer'):
        for kind in ('app', 'test'):
            path = ROOT / (f'app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk' if kind == 'app' else
                           f'app/build/outputs/apk/androidTest/{flavor}/debug/app-{flavor}-debug-androidTest.apk')
            packages[f'{flavor}-{kind}'] = {'path':str(path.relative_to(ROOT)), 'sha256':digest(path), 'bytes':path.stat().st_size}
            run([ADB, '-s', SERIAL, 'install', '-r', '-t', path], timeout=180, log=OUT/f'install-{flavor}-{kind}.log')
    props = {key:run([ADB, '-s', SERIAL, 'shell', 'getprop', key]).strip() for key in
             ('ro.build.version.sdk', 'ro.product.cpu.abi', 'ro.product.model', 'ro.build.fingerprint')}
    props['page_size'] = run([ADB, '-s', SERIAL, 'shell', 'getconf', 'PAGESIZE']).strip()
    source = subprocess.check_output(['git', 'diff', '--binary'], cwd=ROOT)
    (OUT/'build-identity.json').write_text(json.dumps({'head':run(['git','rev-parse','HEAD']).strip(),
        'tracked_diff_sha256':hashlib.sha256(source).hexdigest(), 'serial':SERIAL, 'props':props,
        'packages':packages, 'device_policy':'read-only AVD; no snapshots saved'}, indent=2)+'\n')
    print(json.dumps({'prepared':packages, 'device':props}), flush=True)


def suite(flavor, selected):
    device_ready()
    # Installing is required after any rebuild; record the exact tested APK identity per run.
    stamp = time.strftime('%H%M%S') + '-' + str(os.getpid())
    directory = OUT / (flavor + '-' + stamp)
    directory.mkdir(parents=True)
    identity = {}
    for kind in ('app', 'test'):
        path = ROOT / (f'app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk' if kind == 'app' else
                       f'app/build/outputs/apk/androidTest/{flavor}/debug/app-{flavor}-debug-androidTest.apk')
        identity[kind] = {'sha256':digest(path), 'bytes':path.stat().st_size}
        run([ADB, '-s', SERIAL, 'install', '-r', '-t', path], log=directory/f'install-{kind}.log')
    (directory/'apk-identity.json').write_text(json.dumps(identity, indent=2)+'\n')
    names = ['com.helix.app.' + name for name in (selected or CLASSES)]
    manifest = directory/'classes.json'
    manifest.write_text(json.dumps({'classes':names}, indent=2)+'\n')
    print('RUN_DIRECTORY=' + str(directory.relative_to(ROOT)), flush=True)
    command = [sys.executable, '-u', 'scripts/debug/2026-09-26/run-isolated.py', str(manifest),
               '--flavor',flavor,'--serial',SERIAL,'--timeout','300','--out-dir',str(directory)]
    run(command, timeout=3300, log=directory/'runner.log')
    summary = json.loads((directory/'summary.json').read_text())
    print(json.dumps({'directory':str(directory.relative_to(ROOT)), 'counts':summary['counts'],
                     'missing':summary['missing_count']}, indent=2), flush=True)
    if summary['missing_count'] or any(r['category'] != 'PASS' for r in summary['results']):
        raise RuntimeError('Device suite has non-passing results; inspect per-class logs, never accept process exit alone')


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['prepare','suite'])
    parser.add_argument('--flavor',choices=['consumer','developer'],default='consumer')
    parser.add_argument('--classes',nargs='*')
    args=parser.parse_args()
    if args.action=='prepare': prepare()
    else: suite(args.flavor,args.classes)
