import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
import time

serial, output = sys.argv[1:]
out = Path(output)
root = Path(__file__).resolve().parents[3]
adb = str(Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))) / 'platform-tools/adb')
pkg = 'com.helix.agent'
model = root / os.environ.get('HXA222_MODEL_PATH', 'build/hxa222-closeout/Qwen3-4B-Instruct-2507-Q4_K_M.gguf')
sha = os.environ.get('HXA222_MODEL_SHA', '3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597')
model_size = int(os.environ.get('HXA222_MODEL_SIZE', '2497281120'))
context_tokens = int(os.environ.get('HXA222_CONTEXT', '4096'))
task_timeout = int(os.environ.get('HXA222_TASK_TIMEOUT_SECONDS', '900'))
sample_seconds = float(os.environ.get('HXA222_MEMORY_SAMPLE_SECONDS', '10'))
baseline_idle_ms = int(os.environ.get('HXA222_BASELINE_IDLE_MS', '0'))
clear_app_data = os.environ.get('HXA222_CLEAR_APP_DATA', '0') == '1'
require_test_pass = os.environ.get('HXA222_REQUIRE_TEST_PASS', '1') == '1'
assert 60 <= task_timeout <= 1800
assert 512 <= context_tokens <= 32768
assert 0.5 <= sample_seconds <= 60
assert 0 <= baseline_idle_ms <= 10000
assert hashlib.file_digest(model.open('rb'), 'sha256').hexdigest() == sha
assert model.stat().st_size == model_size

def run(*args, timeout=60):
    return subprocess.run([adb, '-s', serial, *args], check=True, capture_output=True, timeout=timeout).stdout

if clear_app_data:
    assert run('shell', 'pm', 'clear', pkg).strip() == b'Success'
run('shell', 'run-as', pkg, 'mkdir', '-p', 'files/models')
with model.open('rb') as data:
    subprocess.run([adb, '-s', serial, 'shell', 'run-as', pkg, 'sh', '-c', f"'cat > files/models/{sha}.gguf'"], stdin=data, check=True, timeout=120)
# Deliberately offline: fixture is copied through ADB, inference has no network provider.
run('shell', 'svc', 'wifi', 'disable')
run('shell', 'svc', 'data', 'disable')
stop = threading.Event()
def sample():
    with (out / 'runtime-memory.txt').open('wb') as f:
        while not stop.is_set():
            try:
                uptime = float(run('shell', 'cat', '/proc/uptime', timeout=10).decode().split()[0])
                f.write(f'DEVICE_UPTIME_MS={int(uptime * 1000)} HOST_MONOTONIC={time.monotonic()}\n'.encode())
                f.write(run('shell', 'dumpsys', 'meminfo', pkg + ':model_runtime', timeout=10))
                f.flush()
            except (subprocess.SubprocessError, OSError) as error:
                f.write(str(error).encode())
            stop.wait(sample_seconds)
thread = threading.Thread(target=sample, daemon=True)
thread.start()
try:
    classes = os.environ.get('HXA222_CLASSES', 'com.helix.app.localmodel.LocalModelRealTaskDeviceTest')
    result = run('shell', 'am', 'instrument', '-w', '-e', 'class', classes, '-e', 'realModel', 'true', '-e', 'contextTokens', str(context_tokens), '-e', 'taskTimeoutSeconds', str(task_timeout), '-e', 'baselineLoadedIdleMs', str(baseline_idle_ms), '-e', 'modelSha', sha, '-e', 'modelSize', str(model_size), pkg + '.test/com.helix.app.HelixAndroidJUnitRunner', timeout=task_timeout + 180)
    (out / 'real-model-instrumentation.txt').write_bytes(result)
    print(result.decode(), flush=True)
finally:
    stop.set()
    thread.join(15)
    (out / 'real-model-logcat.txt').write_bytes(run('logcat', '-d'))
    for name in ['cold-load-ms.txt', 'warm-reuse-ms.txt', 'load-ms.txt', 'probe.txt', 'trajectory.txt', 'final-durable.txt', 'totals.csv', 'report.md', 'events.txt', 'lifecycle.txt', 'requests.txt', 'baseline-phases.tsv', 'p5-lifecycle.json']:
        try:
            run('shell', 'run-as', pkg, 'test', '-f', 'files/hxa222-evidence/' + name)
            (out / name).write_bytes(run('exec-out', 'run-as', pkg, 'cat', 'files/hxa222-evidence/' + name))
        except subprocess.CalledProcessError:
            pass
    for name in run('shell', 'run-as', pkg, 'ls', 'files/hxa222-evidence').decode().splitlines():
        if name.startswith('request-') and name.endswith('.json') and '/' not in name:
            (out / name).write_bytes(run('exec-out', 'run-as', pkg, 'cat', 'files/hxa222-evidence/' + name))
expected = len([name for name in classes.split(',') if name])
if require_test_pass:
    assert f'OK ({expected} tests)'.encode() in result, 'Real model task/lifecycle did not pass; preserve recorded evidence'
