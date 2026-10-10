"""Bounded synthetic load metrics on the emulator process owned by the stable runner."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time


def validate_report(report):
    assert isinstance(report, dict)
    counts = {'fixtureSessions': 600, 'fixtureMessages': 1000, 'ordinaryBodyBytes': 1024}
    fields = ('directoryMs', 'conversationMs', 'searchMsIncludingDebounce')
    assert set(report) == set(counts) | set(fields)
    assert all(type(report[key]) is int and report[key] == value for key, value in counts.items())
    for field in fields:
        samples = report[field]
        assert isinstance(samples, list) and len(samples) == 5
        assert all(type(value) is int and value >= 0 for value in samples)
    return report


def parse_cold_start(raw, before, after):
    assert type(before) is int and type(after) is int and before > 0 and after > 0 and before != after
    assert re.search(r'^Status: ok$', raw, re.MULTILINE)
    assert re.search(r'^LaunchState: COLD$', raw, re.MULTILINE), 'Each sample must start a new process'
    values = {key: int(value) for key, value in
              re.findall(r'^(ThisTime|TotalTime|WaitTime): (\d+)$', raw, re.MULTILINE)}
    assert 'TotalTime' in values
    return dict(values, beforePid=before, afterPid=after)


def collect(serial, destination):
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from android_process_control import kill_emulator_app

    output = Path(destination)
    owner = json.loads((output / 'owner.json').read_text())
    assert owner['serial'] == serial
    package = os.environ['FIXTURE_PACKAGE']
    assert package in ('com.helix.agent', 'com.helix.agent.developer')
    base = [str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb'), '-s', serial]

    def owned():
        os.kill(owner['pid'], 0)
        actual = subprocess.check_output(base + ['emu', 'avd', 'name'], text=True, timeout=10)
        assert owner['avd'] in actual.splitlines()

    def adb(*args):
        owned()
        return subprocess.check_output(base + list(args), text=True, timeout=35)

    report = validate_report(json.loads(adb('shell', 'run-as', package, 'cat', 'no_backup/projection-load.json')))
    (output / 'projection-load.json').write_text(json.dumps(report, indent=2))
    # Instrumentation normally ends its application process. Establish an unmeasured
    # launch before diagnostics and the five separately verified new-PID samples.
    warmup = adb('shell', 'am', 'start', '-W', '-n', package + '/com.helix.app.MainActivity')
    assert re.search(r'^Status: ok$', warmup, re.MULTILINE)
    (output / 'warmup-launch.txt').write_text(warmup)
    time.sleep(1)
    (output / 'load-graphics.txt').write_text(adb('shell', 'dumpsys', 'gfxinfo', package, 'framestats'))
    (output / 'load-memory.txt').write_text(adb('shell', 'dumpsys', 'meminfo', package))
    starts = []
    for i in range(5):
        current = adb('shell', 'pidof', package).split()
        assert len(current) == 1 and current[0].isdigit()
        kill_emulator_app(base, package, int(current[0]))
        raw = adb('shell', 'am', 'start', '-W', '-n', package + '/com.helix.app.MainActivity')
        (output / f'startup-{i}.txt').write_text(raw)
        after = adb('shell', 'pidof', package).split()
        assert len(after) == 1 and after[0].isdigit()
        starts.append(parse_cold_start(raw, int(current[0]), int(after[0])))
        time.sleep(1)
    (output / 'cold-starts.json').write_text(json.dumps(starts, indent=2))
    print('Synthetic metrics and five actual process-cold launches collected', flush=True)


if __name__ == '__main__':
    collect(*sys.argv[1:])
