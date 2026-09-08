#!/usr/bin/env python3
"""Exercise a real Goal with lost initial output and explicit original-result UI recovery."""
import argparse
import hashlib
import http.server
import importlib.util
import json
import pathlib
import subprocess
import threading


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--normal-completion', action='store_true')
    parser.add_argument('--adb', required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location('proot_kill_fixture', pathlib.Path(__file__).with_name('run-proot-goal-process-kill.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    fixture = module.Fixture
    fixture.successful = True
    fixture.delivery_failure = True
    fixture.normal_completion = args.normal_completion
    args.output.mkdir(parents=True, exist_ok=False)
    base = [args.adb, '-s', args.serial]
    hashes = {}
    for package, path in [
        ('com.helix.agent.developer', 'app/build/outputs/apk/developer/debug/app-developer-debug.apk'),
        ('com.helix.agent.developer.test', 'app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk'),
    ]:
        remote = subprocess.check_output(base + ['shell', 'pm', 'path', package], text=True).strip().removeprefix('package:')
        actual = subprocess.check_output(base + ['shell', 'sha256sum', remote], text=True).split()[0]
        assert actual == hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest(), package
        hashes[package] = actual
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), fixture)
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        command = base + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
            'com.helix.app.eval.ProotGoalProcessKillDeviceTest', '-e', 'proot.goal.kill.port', str(server.server_port),
            '-e', 'proot.goal.kill.phase', 'normal-result' if args.normal_completion else 'delivery-failure', '-e', 'proot.goal.kill.successful', 'true',
            'com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner']
        with (args.output / 'instrumentation.log').open('w') as log:
            subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=100, check=True)
        output = (args.output / 'instrumentation.log').read_text()
        assert 'OK (1 test)' in output and 'PROOT_RESULT_VERIFIED' in output, output
        assert fixture.model_requests == (5 if args.normal_completion else 4), fixture.model_requests
        result = dict(serial=args.serial, normalCompletion=args.normal_completion, tests=1, modelRequests=fixture.model_requests, installedApks=hashes,
                      scope='Scripted model; production Goal settlement and saved-result confirmation UI; output loss injected only in failure mode')
        (args.output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result))
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


if __name__ == '__main__':
    main()
