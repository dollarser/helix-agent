#!/usr/bin/env python3
"""Compatibility entry point; use scripts/with-host-slot.py."""
from pathlib import Path
import runpy

globals().update(runpy.run_path(
    str(Path(__file__).resolve().parents[3] / "scripts/with-host-slot.py"),
    run_name="__main__" if __name__ == "__main__" else __name__,
))
