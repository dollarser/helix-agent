"""Owner-authorized emulator/real-model evaluation; never runs on import or by default."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from instrumentation_junit import parse as parse_instrumentation

CASES = ('gui', 'files', 'recovery', 'combined', 'keyboard')
APP = 'com.helix.agent.developer'
RUNNER = APP + '.test/com.helix.app.HelixAndroidJUnitRunner'
PREFS = 'shared_prefs/helix-mobile-use.xml'
CONFIG = re.compile(r'\s*<string name="mobile-use-global-configuration">.*?</string>', re.S)


def configuration(text):
    match = CONFIG.search(text)
    return match.group(0) if match else ''


def restore_configuration(current, original):
    if '</map>' not in current:
        raise ValueError('Invalid preference document; will not overwrite it')
    return CONFIG.sub('', current).replace('</map>', configuration(original) + '\n</map>')


def validate_result(text, log, trial, case):
    data = json.loads(text)
    if data.get('trial') != trial or data.get('case') != case or not data.get('session'):
        raise ValueError('Result identity does not match this run')
    records = parse_instrumentation(log)
    expected = ('com.helix.app.eval.CurrentModelCapabilityDeviceTest', 'solveOwnedTask')
    if set(records) != {expected} or records[expected][0] != 0:
        raise ValueError('Instrumentation did not pass the requested test')
    if data.get('passed') is not True or data.get('turnState') != 'COMPLETED' or data.get('failure') is not None:
        raise ValueError('Task did not achieve the independently scored result')
    return data


def arguments(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('trial')
    parser.add_argument('cases', nargs='*')
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--allow-real-model', action='store_true')
    parser.add_argument('--without-accessibility', action='store_true')
    args = parser.parse_args(argv)
    args.cases = args.cases or list(CASES)
    if not args.allow_real_model:
        parser.error('Current owner authorization and --allow-real-model are required')
    if not re.fullmatch(r'[a-z0-9-]{1,80}', args.trial):
        parser.error('trial must contain 1-80 lowercase letters, digits or hyphens')
    if len(set(args.cases)) != len(args.cases) or any(case not in CASES + ('notification',) for case in args.cases):
        parser.error('Unknown or duplicate case')
    if not re.fullmatch(r'emulator-[0-9]+', args.serial):
        parser.error('This runner supports an explicitly authorized emulator only')
    return args


def evaluate(args):
    provider, model = os.environ['HELIX_EVAL_PROVIDER'], os.environ['HELIX_EVAL_MODEL']
    if not provider.strip() or not model.strip():
        raise ValueError('Provider and model must be supplied')
    out = Path('build/model-capability-eval')
    out.mkdir(parents=True, exist_ok=True)
    if any(out.glob(args.trial + '-*')):
        raise ValueError('Trial already has evidence; choose a fresh name instead of overwriting it')
    base = ['adb', '-s', args.serial]

    def run(*parts):
        return subprocess.check_output(base + list(parts), text=True, timeout=30).strip()

    original = {key: run('shell', 'settings', 'get', 'secure', key)
                for key in ('enabled_accessibility_services', 'accessibility_enabled')}
    original_prefs = run('exec-out', 'run-as', APP, 'cat', PREFS)
    (out / (args.trial + '-device-settings.json')).write_text(json.dumps(original))
    (out / (args.trial + '-mobile-prefs.xml')).write_text(original_prefs)
    failed = False
    try:
        print(run('install', '-r', 'app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk'), flush=True)
        if args.without_accessibility:
            if original['accessibility_enabled'] not in ('0', 'null'):
                raise ValueError('Disable Accessibility before this bounded run')
        else:
            service = APP + '/com.helix.extensions.mobileuse.automation.HelixAccessibilityService'
            services = [s for s in original['enabled_accessibility_services'].split(':') if s not in ('', 'null')]
            run('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', ':'.join(dict.fromkeys(services + [service])))
            run('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1')
        for case in args.cases:
            print('START', args.trial, case, flush=True)
            remote = f'files/capability-eval/{args.trial}-{case}.json'
            # Remove only this test artifact: a crash must never reuse a prior remote success.
            run('shell', 'run-as', APP, 'rm', '-f', remote)
            command = base + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                              'com.helix.app.eval.CurrentModelCapabilityDeviceTest#solveOwnedTask',
                              '-e', 'helixRealModel', 'true', '-e', 'helixProvider', provider,
                              '-e', 'helixModel', model, '-e', 'helixTrial', args.trial,
                              '-e', 'helixCase', case, RUNNER]
            log_path = out / f'{args.trial}-{case}.log'
            with log_path.open('w') as log:
                subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=310, check=True)
            result = run('exec-out', 'run-as', APP, 'cat', remote)
            (out / f'{args.trial}-{case}.json').write_text(result)
            try:
                data = validate_result(result, log_path.read_text(), args.trial, case)
                print('PASS', case, data['elapsedMs'], data['trajectory'], flush=True)
            except ValueError as error:
                failed = True
                print('FAIL', case, str(error), flush=True)
    except BaseException:
        # A timed-out adb client does not terminate instrumentation or the real model task.
        run('shell', 'am', 'force-stop', APP)
        raise
    finally:
        errors = []
        for key, value in original.items():
            try:
                if value == 'null':
                    run('shell', 'settings', 'delete', 'secure', key)
                else:
                    run('shell', 'settings', 'put', 'secure', key, value)
            except Exception as error:
                errors.append(str(error))
        try:
            current = run('exec-out', 'run-as', APP, 'cat', PREFS)
            if configuration(current) != configuration(original_prefs):
                run('shell', 'am', 'force-stop', APP)
                current = run('exec-out', 'run-as', APP, 'cat', PREFS)
                restored = restore_configuration(current, original_prefs)
                subprocess.run(base + ['shell', 'run-as', APP, 'tee', PREFS], input=restored,
                               text=True, stdout=subprocess.DEVNULL, timeout=30, check=True)
        except Exception as error:
            errors.append(str(error))
        if errors:
            raise RuntimeError('Configuration restoration failed: ' + '; '.join(errors))
    return 1 if failed else 0


if __name__ == '__main__':
    raise SystemExit(evaluate(arguments()))
