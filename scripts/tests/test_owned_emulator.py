"""Fault checks for ownership rejection and instrumentation false greens; no real device access."""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("owned", Path(__file__).resolve().parents[1] / "run-owned-emulator.py")
owned = importlib.util.module_from_spec(spec)
spec.loader.exec_module(owned)


class RunnerTest(unittest.TestCase):
    def verify_target(self, package="com.helix.agent.developer", target=None,
                      test_package="com.helix.agent.developer.test", runner=None):
        manifest = '<manifest package="' + package + '" />'
        test = ('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
                'package="' + test_package + '"><instrumentation android:name=".Runner" '
                'android:targetPackage="' + (target or package) + '" /></manifest>')
        with patch.object(owned.subprocess, "check_output", side_effect=[manifest, test]):
            return owned.verify_apk_target(Path("/fixture/sdk"), Path("app.apk"), Path("test.apk"),
                                           runner or test_package + "/.Runner")

    def test_integrated_product_target_matches(self):
        self.assertEqual(self.verify_target(), "com.helix.agent.developer")

    def test_retired_runtime_and_self_host_cannot_be_product_target(self):
        for package in ("com.helix.runtime.cli", "com.helix.runtime.cli.app.test",
                        "com.helix.runtime.proot", "com.helix.runtime.proot.app.test"):
            with self.subTest(package=package), self.assertRaisesRegex(ValueError, "integrated Helix APK"):
                self.verify_target(package=package)

    def test_wrong_flavor_or_runner_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "does not target"):
            self.verify_target(target="com.helix.agent")
        with self.assertRaisesRegex(ValueError, "Runner package"):
            self.verify_target(runner="other.test/.Runner")
        with self.assertRaisesRegex(ValueError, "does not target"):
            self.verify_target(runner="com.helix.agent.developer.test/.Missing")

    def test_other_library_targets_remain_supported(self):
        self.assertEqual(self.verify_target(package="com.helix.tools.browser.test",
                                           test_package="com.helix.tools.browser.test"),
                         "com.helix.tools.browser.test")

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
            owned.run(SimpleNamespace(port=5598, reboot_after_setup=False))
        launch.assert_not_called()


if __name__ == "__main__":
    unittest.main()
