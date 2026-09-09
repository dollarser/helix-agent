"""Fault checks for ownership rejection and instrumentation false greens; no real device access."""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("owned", Path(__file__).with_name("run-owned-emulator.py"))
owned = importlib.util.module_from_spec(spec)
spec.loader.exec_module(owned)


class RunnerTest(unittest.TestCase):
    def test_nonempty_junit_only(self):
        self.assertTrue(owned.passed("\nOK (2 tests)\n"))
        for output in ("BUILD SUCCESSFUL", "OK (0 tests)", "FAILURES!!!\nTests run: 1, Failures: 1",
                       "OK (2 tests)\nINSTRUMENTATION_FAILED", "OK (1 test)\nProcess crashed"):
            self.assertFalse(owned.passed(output), output)

    @patch.dict(owned.os.environ, {"ANDROID_HOME": "/fixture/sdk"})
    @patch.object(owned.subprocess, "Popen")
    @patch.object(owned.subprocess, "check_output", return_value="List of devices attached\nemulator-5598\tdevice\n")
    def test_existing_serial_is_never_borrowed(self, check_output, launch):
        with self.assertRaisesRegex(RuntimeError, "Refusing existing device"):
            owned.run(SimpleNamespace(port=5598))
        launch.assert_not_called()


if __name__ == "__main__":
    unittest.main()
