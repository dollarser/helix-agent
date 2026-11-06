#!/usr/bin/env python3
"""Check actual pushed commits in isolated Git snapshots; never stash/reset user work."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time

ZERO = '0' * 40
OID = re.compile(r'[0-9a-f]{40}')
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('push_ci_plan', ROOT / 'scripts/ci/plan.py')
ci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci)


def clean_env():
    env = dict(os.environ)
    local = subprocess.check_output(['git', 'rev-parse', '--local-env-vars'], text=True).split()
    for name in set(local) | {'GIT_ATTR_SOURCE', 'GIT_CONFIG_COUNT'}:
        env.pop(name, None)
    for name in list(env):
        if name.startswith(('GIT_CONFIG_KEY_', 'GIT_CONFIG_VALUE_')):
            env.pop(name)
    return env


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args], env=clean_env(), text=True).strip()


def updates(raw):
    result = []
    for line in raw.splitlines():
        fields = line.split()
        if len(fields) != 4 or not OID.fullmatch(fields[1]) or not OID.fullmatch(fields[3]):
            raise ValueError('Malformed pre-push ref update')
        if fields[1] != ZERO:
            result.append(tuple(fields))
    return result


def ancestor(root, base, head):
    return bool(OID.fullmatch(base)) and subprocess.run(
        ['git', '-C', str(root), 'merge-base', '--is-ancestor', base, head], env=clean_env(),
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    ).returncode == 0


def repository(url):
    match = re.fullmatch(r'(?:git@github\.com:|https://github\.com/|ssh://git@github\.com/)([\w.-]+/[\w.-]+?)(?:\.git)?', url)
    if not match:
        raise ValueError('Automatic CI baseline needs a GitHub remote; supply an explicit reviewed CI_BASE')
    return match[1]


def github_get(repo):
    cache = {}

    def get(path):
        if path not in cache:
            raw = subprocess.check_output(['gh', 'api', f'repos/{repo}/{path}'], env=clean_env(), text=True, timeout=20)
            cache[path] = json.loads(raw)
        return cache[path]
    return get


def baseline(root, head, get, explicit=None):
    value = explicit or ci.proven_base(get, lambda sha: ancestor(root, sha, head))
    if not value or not ancestor(root, value, head):
        raise ValueError('No verified ancestor CI baseline; fetch history or set CI_BASE to a reviewed ancestor. No HEAD^ fallback.')
    return value


def fingerprint():
    tools = [sys.version, sys.platform, Path(__file__).read_text()]
    for command in (['git', '--version'], ['rg', '--version']):
        tools.append(subprocess.check_output(command, env=clean_env(), text=True))
    for value, expected in [('helix-hook-probe\n', 0), ('miss\n', 1)]:
        result = subprocess.run(['rg', '--pcre2', '--quiet', '^helix-hook-probe$'], input=value,
                                env=clean_env(), text=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if result.returncode != expected:
            raise RuntimeError('Secret-scanner dependency probe failed; source cache cannot be reused')
    return hashlib.sha256('\n'.join(tools).encode()).hexdigest()


def check_snapshot(root, head, base, cache, identity):
    started = time.monotonic()
    with tempfile.TemporaryDirectory(prefix='push-', dir=cache) as directory:
        snapshot = Path(directory) / 'source'
        subprocess.run(['git', '-c', 'core.hooksPath=/dev/null', 'clone', '--shared', '--no-checkout', '--quiet',
                        '--', str(root), str(snapshot)], env=clean_env(), check=True)
        git(snapshot, 'config', 'core.hooksPath', '/dev/null')
        git(snapshot, 'config', 'core.attributesFile', '/dev/null')
        git(snapshot, 'config', 'core.whitespace', 'blank-at-eol,blank-at-eof,space-before-tab')
        git(snapshot, '-c', 'advice.detachedHead=false', 'checkout', '--quiet', '--detach', head)
        env = clean_env()
        env.update(CI_BASE=base, GIT_ATTR_NOSYSTEM='1')
        # Suppress diff contents: whitespace diagnostics can contain secret-bearing lines.
        result = subprocess.run([sys.executable, 'scripts/ci/plan.py', '--check-whitespace'], cwd=snapshot,
                                env=env, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if result.returncode:
            raise RuntimeError(f'Committed range whitespace failed: {base}..{head}; inspect locally. Push blocked.')
        receipt = cache / f'{head}-{identity}.json'
        try:
            prior = json.loads(receipt.read_text())
        except (OSError, ValueError):
            prior = None
        if prior == {'commit': head, 'tools': identity, 'source': 'passed'}:
            print(f'pre-push {head[:12]}: range passed; exact-commit source cache reused ({time.monotonic()-started:.2f}s).')
            return
        command = snapshot / 'scripts/check-all.sh'
        if not command.is_file():
            raise RuntimeError('Pushed snapshot has no source gate; refusing to report a pass')
        log = cache / f'{head}-source.log'
        print(f'pre-push {head[:12]}: running source checks; log: {log}', flush=True)
        with log.open('w') as output:
            result = subprocess.run(['bash', str(command), '--source'], cwd=snapshot, env=env,
                                    stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT)
        if result.returncode:
            raise RuntimeError(f'Committed source gate failed (exit {result.returncode}); see {log}')
        with tempfile.NamedTemporaryFile(mode='w', dir=cache, delete=False) as output:
            output.write(json.dumps({'commit': head, 'tools': identity, 'source': 'passed'}))
            temporary = Path(output.name)
        try:
            os.replace(temporary, receipt)
        finally:
            temporary.unlink(missing_ok=True)
        print(f'pre-push {head[:12]}: committed range and source gate passed ({time.monotonic()-started:.2f}s).')


def run(root, refs, get, explicit=None):
    started = time.monotonic()
    pending = updates(refs)
    if not pending:
        print('pre-push: no non-deleted ref updates; no source pass claimed.')
        return
    cache = root / 'build/hooks'
    cache.mkdir(parents=True, exist_ok=True)
    identity = fingerprint()
    checked = set()
    for local_ref, oid, remote_ref, remote_oid in pending:
        head = git(root, 'rev-parse', '--verify', oid + '^{commit}')
        if head in checked:
            continue
        base = baseline(root, head, get, explicit)
        print(f'pre-push {local_ref} → {remote_ref}: checking {base[:12]}..{head[:12]}', flush=True)
        check_snapshot(root, head, base, cache, identity)
        checked.add(head)
    print(f'pre-push: {len(checked)} committed snapshots checked in {time.monotonic()-started:.2f}s.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('remote')
    parser.add_argument('url')
    args = parser.parse_args()
    root = Path(git(Path.cwd(), 'rev-parse', '--show-toplevel'))
    raw = sys.stdin.read()
    if not updates(raw):
        run(root, raw, None)
        return
    explicit = os.environ.get('CI_BASE')
    get = github_get(repository(args.url)) if not explicit else None
    run(root, raw, get, explicit)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.SubprocessError) as error:
        print(f'pre-push blocked: {type(error).__name__}: {error}', file=sys.stderr)
        sys.exit(1)
