"""Owned-emulator follow-up: upstream task setup/oracle, real Helix task execution."""
import json
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import time
import traceback
import types

ROOT = Path(__file__).resolve().parents[3]
PYTHON = ROOT / 'build/public-eval/android-env/bin/python'
if Path(sys.prefix).resolve() != PYTHON.parent.parent.resolve():
    os.execv(str(PYTHON), [str(PYTHON), __file__, *sys.argv[1:]])
sys.path.insert(0, str(ROOT / 'build/public-eval/android_world'))
from absl import logging
logging.set_verbosity(logging.ERROR)  # Upstream controller logs its environment at INFO.
from android_env.components.adb_controller import AdbController
from android_env.components.adb_call_parser import AdbCallParser
from android_env.components.config_classes import AdbControllerConfig
from android_world.task_evals.single.system import SystemBrightnessMin, SystemBrightnessMax

serial, destination = sys.argv[1:]
output = Path(destination).resolve()
owner = json.loads((output / 'owner.json').read_text())
assert owner['serial'] == serial and serial.startswith('emulator-')
os.kill(owner['pid'], 0)
adb = str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb')

def command(*args, timeout=60):
    os.kill(owner['pid'], 0)
    return subprocess.run([adb, '-s', serial, *args], text=True, capture_output=True,
                          check=True, timeout=timeout)

parser = AdbCallParser(AdbController(AdbControllerConfig(adb_path=adb, device_name=serial)))
controller = types.SimpleNamespace(execute_adb_call=parser.parse)
env = types.SimpleNamespace(controller=controller, interaction_cache='')
launcher = command('shell', 'cmd', 'package', 'resolve-activity', '--brief', '-a',
                   'android.intent.action.MAIN', '-c', 'android.intent.category.HOME').stdout.strip().splitlines()[-1].split('/')[0]
assert '.' in launcher
manifest = dict(source_commit=subprocess.check_output(['git', '-C', str(ROOT / 'build/public-eval/android_world'), 'rev-parse', 'HEAD'], text=True).strip(),
                helix_commit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                tasks=['SystemBrightnessMin', 'SystemBrightnessMax'], repeats=1,
                adaptation='API36 arm64 owned AVD, Helix accessibility action space; upstream initialization and oracle unchanged; no official leaderboard score',
                model='Qwen3.8-27B', max_model_calls=32, max_steps=32, max_output_tokens=4096, max_total_tokens=1000000,
                temperature='provider default', task_timeout_seconds=240,
                approvals='Only ui click/long_click/set_text/scroll/back/home; production token, package and sensitive UI checks remain',
                launcher=launcher)
manifest['source_sha256'] = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                             for path in [Path(__file__).resolve(),
                                          ROOT / 'app/src/androidTestDeveloper/kotlin/com/helix/app/eval/AndroidWorldPilotDeviceTest.kt',
                                          ROOT / 'app/src/main/kotlin/com/helix/app/chat/ModelToolExposureOrder.kt']}
(output / 'androidworld-source.diff').write_bytes(subprocess.check_output(['git', 'diff'], cwd=ROOT))
(output / 'androidworld-manifest.json').write_text(json.dumps(manifest, indent=2))
results = []
for task_class, target in [(SystemBrightnessMin, 'min'), (SystemBrightnessMax, 'max')]:
    started = time.monotonic()
    result = dict(task=task_class.__name__, target=target)
    try:
        task = task_class({'max_or_min': target})
        task.initialize_task(env)
        result['goal'] = task.goal
        result['before'] = command('shell', 'settings', 'get', 'system', 'screen_brightness').stdout.strip()
        assert result['before'] == ('255' if target == 'min' else '1'), 'upstream initial state mismatch'
        assert task.is_successful(env) == 0.0, 'oracle must reject initial state'
        run = command('shell', 'am', 'instrument', '-w', '-e', 'class',
                      'com.helix.app.eval.AndroidWorldPilotDeviceTest', '-e', 'helix.androidWorld', 'true',
                      '-e', 'brightness', target, '-e', 'launcher', launcher,
                      'com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner', timeout=300)
        (output / f'androidworld-{target}-instrumentation.txt').write_text(run.stdout + run.stderr)
        evidence = command('shell', 'run-as', 'com.helix.agent.developer', 'cat', f'files/androidworld-{target}.json').stdout
        (output / f'androidworld-{target}-agent.json').write_text(evidence)
        result['agent'] = json.loads(evidence)
        result['after'] = command('shell', 'settings', 'get', 'system', 'screen_brightness').stdout.strip()
        result['score'] = task.is_successful(env)
        result['instrumentation_ok'] = 'OK (1 test)' in run.stdout and 'FAILURES!!!' not in run.stdout
        result['status'] = ('PASS' if result['score'] == 1.0 else 'FAIL') if result['instrumentation_ok'] else 'ERROR'
    except Exception as exc:
        result.update(status='ERROR', error=f'{type(exc).__name__}: {exc}', traceback=traceback.format_exc())
    result['elapsed_seconds'] = round(time.monotonic() - started, 3)
    results.append(result)
    (output / 'androidworld-results.json').write_text(json.dumps(results, indent=2))
    print(task_class.__name__, result['status'], result.get('error', ''), flush=True)
