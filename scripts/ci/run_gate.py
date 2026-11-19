#!/usr/bin/env python3
"""Bound a CI gate, stream its output and retain diagnostics before the job deadline."""
import argparse
import json
import os
from pathlib import Path
import selectors
import signal
import subprocess
import sys
import time


def diagnostics(directory, include_jvm=False):
    directory.mkdir(parents=True, exist_ok=True)
    (directory / 'captured-at.txt').write_text(str(time.time()) + '\n')
    commands = [('processes', ['ps', '-eo', 'pid,ppid,etime,pcpu,rss,comm']),
                ('disk', ['df', '-h']), ('memory', ['free', '-m'])]
    for name, command in commands:
        try:
            result = subprocess.run(command, capture_output=True, text=True, timeout=5)
            (directory / (name + '.txt')).write_text(result.stdout + result.stderr)
        except (OSError, subprocess.TimeoutExpired) as error:
            (directory / (name + '.txt')).write_text(type(error).__name__)
    if not include_jvm or os.environ.get('GITHUB_ACTIONS') != 'true':
        return
    try:
        listing = subprocess.check_output(['jcmd', '-l'], text=True, timeout=5)
        for line in [item for item in listing.splitlines()
                     if any(name in item for name in ('GradleDaemon', 'KotlinCompileDaemon'))][:3]:
            pid = line.split()[0]
            if pid.isdigit():
                result = subprocess.run(['jcmd', pid, 'Thread.print'], capture_output=True,
                                        text=True, timeout=8)
                (directory / ('jvm-' + pid + '.txt')).write_text(result.stdout + result.stderr)
    except (OSError, subprocess.SubprocessError):
        (directory / 'jvm-unavailable.txt').write_text('JVM diagnostic collection unavailable\n')


def run(gate, command, directory, timeout, heartbeat):
    directory.mkdir(parents=True, exist_ok=True)
    start = time.monotonic()
    deadline = start + timeout
    if os.environ.get('HELIX_CI_JOB_DEADLINE'):
        deadline = min(deadline, start + float(os.environ['HELIX_CI_JOB_DEADLINE']) - time.time())
    interrupted = []
    old = {sig: signal.signal(sig, lambda signum, _: interrupted.append(signum))
           for sig in (signal.SIGTERM, signal.SIGINT)}
    result = 124
    reason = 'deadline'
    last_task = None
    process = None
    pending = b''
    try:
        if deadline <= start:
            return result
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   start_new_session=True)
        selector = selectors.DefaultSelector()
        selector.register(process.stdout, selectors.EVENT_READ)
        next_heartbeat = start + heartbeat
        with (directory / (gate + '.log')).open('wb') as log:
            while selector.get_map() or process.poll() is None:
                now = time.monotonic()
                if interrupted or now >= deadline:
                    reason = 'signal' if interrupted else 'deadline'
                    result = 128 + interrupted[0] if interrupted else 124
                    diagnostics(directory / (gate + '-diagnostics'), include_jvm=True)
                    try:
                        os.killpg(process.pid, signal.SIGTERM)
                    except ProcessLookupError:
                        pass
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        pass
                    # The launcher can exit before its descendants; kill the whole owned group.
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    break
                if now >= next_heartbeat:
                    print(f'[ci:{gate}] elapsed={int(now-start)}s last-emitted-task={last_task}', flush=True)
                    diagnostics(directory / (gate + '-diagnostics'))
                    next_heartbeat = now + heartbeat
                for key, _ in selector.select(timeout=min(1, max(0, deadline-now))):
                    chunk = os.read(key.fileobj.fileno(), 65536)
                    if not chunk:
                        selector.unregister(key.fileobj)
                        continue
                    log.write(chunk)
                    log.flush()
                    sys.stdout.buffer.write(chunk)
                    sys.stdout.buffer.flush()
                    pending = (pending + chunk)[-131072:]
                    lines = pending.split(b'\n')
                    pending = lines.pop()
                    for line in lines:
                        if line.startswith(b'> Task '):
                            last_task = line.decode(errors='replace')
            else:
                result = process.wait(timeout=5)
                reason = 'exit'
        selector.close()
        if result < 0:
            result = 128 - result
        return result
    except Exception:
        result = 1
        reason = 'supervisor-error'
        raise
    finally:
        if process is not None and process.poll() is None:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=5)
        for sig, handler in old.items():
            signal.signal(sig, handler)
        elapsed = round(time.monotonic() - start, 3)
        record = {'gate': gate, 'seconds': elapsed, 'exitCode': result,
                  'reason': reason, 'lastEmittedTask': last_task}
        (directory / (gate + '.json')).write_text(json.dumps(record, indent=2) + '\n')
        (directory / (gate + '.tsv')).write_text(f'{gate}\t{elapsed}\t{result}\n')
        if os.environ.get('GITHUB_STEP_SUMMARY'):
            with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
                summary.write(f'\n### {gate}\n\nDuration: {elapsed}s. Exit: {result} ({reason}). '
                              f'Last emitted task: `{last_task}`.\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('gate')
    parser.add_argument('--timeout', type=float, default=45 * 60)
    parser.add_argument('--heartbeat', type=float, default=300)
    args = parser.parse_args()
    if args.timeout <= 0 or args.heartbeat <= 0:
        parser.error('Timeout and heartbeat must be positive')
    sys.exit(run(args.gate, ['./scripts/check-all.sh', '--' + args.gate],
                 Path('build/ci'), args.timeout, args.heartbeat))
