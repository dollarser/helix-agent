#!/usr/bin/env python3
"""Serialize heavy local builds/device runs across worktrees; kernel releases the lock on exit."""
import fcntl
from pathlib import Path
import subprocess
import sys

args = sys.argv[1:]
if args[:1] == ["--"]:
    args = args[1:]
if not args:
    raise SystemExit("usage: with-host-slot.py -- command [args ...]")
common = Path(subprocess.check_output([
    "git", "rev-parse", "--path-format=absolute", "--git-common-dir",
], text=True).strip())
lock = common.parent / "build" / "claude-development-host.lock"
lock.parent.mkdir(parents=True, exist_ok=True)
with lock.open("a") as handle:
    print("Waiting for the shared build/device slot", file=sys.stderr, flush=True)
    fcntl.flock(handle, fcntl.LOCK_EX)
    print("Acquired the shared build/device slot", file=sys.stderr, flush=True)
    raise SystemExit(subprocess.run(args).returncode)
