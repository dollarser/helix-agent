"""Retained failure-propagation regression; stub every device command, never launch an emulator."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[1] / 'debug/2026-09-17/hxa192/run-hxa192-matrix.sh'


class MatrixExitTest(unittest.TestCase):
    def test_every_nonzero_quadrant_fails_and_later_results_are_retained(self):
        for exit_code in (0, 1, 10, 99, 100, 127, 137, 255):
            with self.subTest(exit_code=exit_code), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                stub = root / 'scripts/debug/2026-09-17/hxa192/run-hxa192-device.sh'
                stub.parent.mkdir(parents=True)
                stub.write_text(f'#!/bin/sh\nif [ "$1-$2" = consumer-29 ]; then exit {exit_code}; fi\nexit 0\n')
                stub.chmod(0o755)
                result = subprocess.run(['sh', str(SOURCE), str(root / 'output')], cwd=root,
                                        capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, 0 if exit_code == 0 else 1)
                rows = [json.loads(line) for line in (root / 'output/manifest.jsonl').read_text().splitlines()]
                self.assertEqual({row['quadrant'] for row in rows},
                                 {'consumer-api29', 'consumer-api36', 'developer-api29', 'developer-api36'})
                self.assertEqual(rows[0]['exit'], exit_code)
                self.assertTrue(all(row['exit'] == 0 for row in rows[1:]))
