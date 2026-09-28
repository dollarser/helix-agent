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
model = root / os.environ.get('HXA222_MODEL_PATH', 'build/hxa222-real-model/Qwen3-0.6B-Q4_K_M.gguf')
sha = os.environ.get('HXA222_MODEL_SHA', 'ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a')
model_size = int(os.environ.get('HXA222_MODEL_SIZE', '396705472'))
context_tokens = int(os.environ.get('HXA222_CONTEXT', '8192'))
task_timeout = int(os.environ.get('HXA222_TASK_TIMEOUT_SECONDS', '900'))
assert 60 <= task_timeout <= 1800
assert 512 <= context_tokens <= 32768
assert hashlib.file_digest(model.open('rb'), 'sha256').hexdigest() == sha
assert model.stat().st_size == model_size

def run(*args, timeout=60):
    return subprocess.run([adb, '-s', serial, *args], check=True, capture_output=True, timeout=timeout).stdout

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
                f.write(f'Elapsed={time.monotonic()}\n'.encode())
                f.write(run('shell', 'dumpsys', 'meminfo', pkg + ':model_runtime', timeout=10))
                f.flush()
            except (subprocess.SubprocessError, OSError) as error:
                f.write(str(error).encode())
            stop.wait(10)
thread = threading.Thread(target=sample, daemon=True)
thread.start()
try:
    classes = os.environ.get('HXA222_CLASSES', 'com.helix.app.localmodel.LocalModelRealTaskDeviceTest')
    result = run('shell', 'am', 'instrument', '-w', '-e', 'class', classes, '-e', 'realModel', 'true', '-e', 'contextTokens', str(context_tokens), '-e', 'taskTimeoutSeconds', str(task_timeout), '-e', 'modelSha', sha, '-e', 'modelSize', str(model_size), pkg + '.test/com.helix.app.HelixAndroidJUnitRunner', timeout=task_timeout + 180)
    (out / 'real-model-instrumentation.txt').write_bytes(result)
    print(result.decode(), flush=True)
finally:
    stop.set()
    thread.join(15)
    (out / 'real-model-logcat.txt').write_bytes(run('logcat', '-d'))
    for name in ['load-ms.txt', 'probe.txt', 'trajectory.txt', 'final-durable.txt', 'totals.csv', 'report.md', 'events.txt', 'lifecycle.txt', 'requests.txt']:
        try:
            run('shell', 'run-as', pkg, 'test', '-f', 'files/hxa222-evidence/' + name)
            (out / name).write_bytes(run('exec-out', 'run-as', pkg, 'cat', 'files/hxa222-evidence/' + name))
        except subprocess.CalledProcessError:
            pass
    for name in run('shell', 'run-as', pkg, 'ls', 'files/hxa222-evidence').decode().splitlines():
        if name.startswith('request-') and name.endswith('.json') and '/' not in name:
            (out / name).write_bytes(run('exec-out', 'run-as', pkg, 'cat', 'files/hxa222-evidence/' + name))
assert b'OK (1 test)' in result, 'Real model task did not pass; preserve recorded evidence'
