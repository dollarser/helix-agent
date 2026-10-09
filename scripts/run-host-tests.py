#!/usr/bin/env python3
"""Discover all host-only fixtures; empty or entirely skipped suites cannot pass."""
from pathlib import Path
import sys
from typing import TextIO
import unittest


def run(directory: Path, stream: TextIO) -> int:
    suite = unittest.TestLoader().discover(str(directory), pattern="test_*.py")
    if suite.countTestCases() == 0:
        print("No host tests discovered", file=stream)
        return 1
    result = unittest.TextTestRunner(stream=stream).run(suite)
    if result.testsRun == len(result.skipped):
        print("No host tests executed: every discovered test was skipped", file=stream)
        return 1
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    sys.exit(run(Path(__file__).resolve().parent / "tests", sys.stderr))
