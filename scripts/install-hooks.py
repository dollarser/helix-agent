#!/usr/bin/env python3
"""Inspect by default; opt in to repository-local hooks without replacing other hooks."""
import argparse
import os
from pathlib import Path
import subprocess
import sys


def git(*args):
    return subprocess.run(['git', *args], capture_output=True, text=True, check=False)


def install(apply=False, worktree=False):
    root = git('rev-parse', '--show-toplevel')
    if root.returncode:
        raise RuntimeError('Run inside the Helix repository')
    root = Path(root.stdout.strip())
    configured = git('config', '--get', 'core.hooksPath')
    current = configured.stdout.strip()
    default = Path(git('rev-parse', '--git-path', 'hooks').stdout.strip())
    if not default.is_absolute():
        default = Path.cwd() / default
    existing = sorted(p.name for p in default.iterdir() if not p.name.endswith('.sample') and os.access(p, os.X_OK)) if default.is_dir() else []
    linked = git('worktree', 'list', '--porcelain').stdout.count('worktree ')
    independent = git('config', '--bool', '--get', 'extensions.worktreeConfig').stdout.strip() == 'true'
    own = current in {'.githooks', str(root / '.githooks')}
    print(f'Project hooks: {"enabled" if own else "not enabled"}; linked worktrees: {linked}.')
    if configured.returncode == 0 and not own:
        raise RuntimeError('Existing core.hooksPath is configured; preserve it and integrate manually')
    if existing and not own:
        raise RuntimeError('Existing default hooks would be hidden; integrate manually instead')
    if worktree and not independent:
        raise RuntimeError('Worktree config is not enabled; do not change shared extensions automatically. Use per-command hooks.')
    if apply and linked > 1 and not own and not worktree:
        raise RuntimeError('Repository config is shared by worktrees; choose a worktree-local integration manually')
    for name in ['pre-commit', 'pre-push']:
        path = root / '.githooks' / name
        if not path.is_file() or not os.access(path, os.X_OK):
            raise RuntimeError(f'Missing executable project hook: {name}')
    if apply and not own:
        result = git('config', '--worktree' if worktree else '--local', 'core.hooksPath', '.githooks')
        if result.returncode:
            raise RuntimeError('Could not install repository-local hooks')
        print(f'Installed {"worktree" if worktree else "repository"}-local hooks; global Git configuration unchanged.')
    elif not own:
        option = ' --worktree' if linked > 1 and independent else ''
        if linked > 1 and not independent:
            print('No configuration changed. Shared worktrees: use git -c core.hooksPath=.githooks for one operation.')
        else:
            print('No configuration changed. Opt in with: python3 scripts/install-hooks.py --install' + option)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--install', action='store_true')
    parser.add_argument('--worktree', action='store_true', help='Use existing independent worktree configuration only')
    args = parser.parse_args()
    try:
        install(args.install, args.worktree)
    except (OSError, RuntimeError) as error:
        print(f'hook setup blocked: {error}', file=sys.stderr)
        sys.exit(1)
