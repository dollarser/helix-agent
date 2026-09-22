#!/usr/bin/env python3
"""Compatibility entry point; use scripts/run-owned-emulator.py."""
from pathlib import Path
import runpy

globals().update(runpy.run_path(
    str(Path(__file__).resolve().parents[3] / "scripts/run-owned-emulator.py"),
    run_name="__main__" if __name__ == "__main__" else __name__,
))
