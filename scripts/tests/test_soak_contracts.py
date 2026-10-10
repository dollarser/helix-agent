"""Run retained pure soak fault suites in isolated host processes; never start a device.

The legacy executables own signal handlers and temporary fixtures. Isolation keeps those
handlers out of unittest discovery while preserving their original assertions.
"""
from pathlib import Path
import subprocess
import sys
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]


class SoakContractsTest(unittest.TestCase):
    def test_all_retained_fault_and_accounting_suites(self):
        for name in ('test-hxa103-runner-logic.py', 'test-run-browser-autofill-soak-faults.py',
                     'test-run-ev04-mainapp-soak-logic.py', 'test-run-ev04-mainapp-soak-fixture-logic.py'):
            with self.subTest(suite=name):
                result = subprocess.run([sys.executable, str(SCRIPTS / name)],
                                        capture_output=True, text=True, timeout=45)
                self.assertEqual(result.returncode, 0, (result.stdout + result.stderr)[-8000:])
