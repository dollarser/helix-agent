"""Shared, fail-closed helpers for an OFFLINE-INTEGRATION preparation kit, not an app service."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time
from typing import Any

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
OUT = ROOT / 'build/ffmpeg-ready-2026-10-01'
RECIPE = json.loads((HERE / 'recipe.json').read_text())


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def identity(value: Any) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def atomic_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix='.' + path.name + '-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as stream:
            json.dump(value, stream, indent=2, ensure_ascii=False, allow_nan=False)
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def tree_manifest(root: Path) -> dict:
    require(root.is_dir() and not root.is_symlink(), f'Missing real directory: {root}')
    result = {}
    for path in sorted(root.rglob('*')):
        name = path.relative_to(root).as_posix()
        if path.is_symlink():
            require(path.resolve().is_relative_to(root.resolve()), f'Escaping symlink: {name}')
            result[name] = {'symlink': os.readlink(path)}
        elif path.is_file():
            result[name] = {'bytes': path.stat().st_size, 'sha256': digest(path)}
        elif not path.is_dir():
            raise RuntimeError(f'Unexpected filesystem node: {name}')
    return result


def proof_matches(path: Path, key: str, files: Path) -> bool:
    if not path.is_file() or not files.is_dir():
        return False
    try:
        proof = json.loads(path.read_text())
        return proof['key'] == key and proof['files'] == tree_manifest(files)
    except (ValueError, KeyError, OSError, RuntimeError):
        return False


def run(argv: list, *, log: Path, cwd: Path | None = None, env: dict | None = None,
        timeout: int = 1200, expect: tuple[int, ...] = (0,)) -> str:
    """Bound the entire owned process group, not only a make/cmake parent."""
    log.parent.mkdir(parents=True, exist_ok=True)
    command = [str(x) for x in argv]
    metadata = {'argv': command, 'started_at': time.time(), 'timed_out': False}
    with log.open('wb') as output:
        process = subprocess.Popen(command, cwd=cwd or ROOT, env=env, stdout=output,
                                   stderr=subprocess.STDOUT, start_new_session=True)
        try:
            process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            metadata['timed_out'] = True
            for signum, grace in ((signal.SIGTERM, 2), (signal.SIGKILL, 5)):
                try:
                    os.killpg(process.pid, signum)
                except ProcessLookupError:
                    pass
                try:
                    process.wait(timeout=grace)
                except subprocess.TimeoutExpired:
                    continue
                # Kill any descendant that survived parent termination as well.
                if signum == signal.SIGTERM:
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                break
            metadata['exit_code'] = process.poll()
            atomic_json(log.with_suffix(log.suffix + '.json'), metadata)
            raise RuntimeError(f'Process deadline exceeded; no success claimed: {log.name}')
        except BaseException:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=5)
            raise
    metadata['exit_code'] = process.returncode
    atomic_json(log.with_suffix(log.suffix + '.json'), metadata)
    # Logs stay on disk; do not allocate arbitrarily large compiler or decoder output.
    with log.open('rb') as stream:
        stream.seek(max(0, log.stat().st_size - 12000))
        tail = stream.read().decode('utf-8', errors='replace')
    require(process.returncode in expect, f'{log.name}: exit {process.returncode}\n{tail}')
    return tail


def components(profile: str, target: str) -> dict[str, list[str]]:
    require(profile in ('ready', 'ready-av1'), 'Unknown profile')
    require(target in ('host', 'arm64-v8a'), 'Unsupported target')
    result = {key: value.split(',') for key, value in RECIPE['components'].items()}
    additions = []
    if profile == 'ready-av1':
        additions.append(RECIPE['av1_additions'])
    if target != 'host':
        additions.append(RECIPE['native_additions'])
    for group in additions:
        for key, value in group.items():
            result[key] += value.split(',')
    return {key: sorted(set(value)) for key, value in result.items()}


def validate_components(config: str, general: str, profile: str, target: str) -> dict:
    """Every requested item must actually be enabled; configure warnings are not success."""
    import re
    enabled = set(re.findall(r'^#define CONFIG_(\w+) 1$', config, re.MULTILINE))
    requested = {f'{name.upper()}_{kind.upper()}' for kind, names in components(profile, target).items() for name in names}
    require(not (requested - enabled), 'Missing requested components: ' + ', '.join(sorted(requested - enabled)))
    for name in ('GPL', 'NONFREE', 'VERSION3', 'NETWORK', 'AVDEVICE'):
        require(f'#define CONFIG_{name} 0' in general, f'Unexpected {name} enabled')
    protocols = {item.removesuffix('_PROTOCOL').lower() for item in enabled if item.endswith('_PROTOCOL')}
    require(protocols == {'file', 'fd', 'pipe'}, 'Protocol allowlist drift')
    if profile == 'ready':
        require('LIBDAV1D_DECODER' not in enabled, 'Optional AV1 leaked into base')
    return {'requested': sorted(requested), 'enabled': sorted(enabled),
            'compiled_only': True, 'device_status': 'not_requested'}
