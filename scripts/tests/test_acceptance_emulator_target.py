"""The acceptance runner shares the frozen-APK safety checks before launching a device."""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "acceptance_emulator", Path(__file__).resolve().parents[1] / "run-owned-acceptance-emulator.py",
)
acceptance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(acceptance)


class AcceptanceTargetTest(unittest.TestCase):
    def test_frozen_manifest_rejection_precedes_emulator_or_avd_changes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            app, test = root / "app-input.apk", root / "test-input.apk"
            app.write_bytes(b"app fixture")
            test.write_bytes(b"test fixture")
            args = SimpleNamespace(port=5666, apk=app, test_apk=test, runner="fixture/Runner",
                                   output=root / "run", clean_install=True)
            with patch.dict(acceptance.os.environ, {"ANDROID_HOME": "/fixture/sdk"}), \
                    patch.object(acceptance.subprocess, "check_output", return_value="List of devices attached\n"), \
                    patch.object(acceptance.socket, "socket"), \
                    patch.object(acceptance.owned_emulator, "verify_apk_target", side_effect=ValueError("wrong target")) as verify, \
                    patch.object(acceptance, "enable_hardware_keyboard") as keyboard, \
                    patch.object(acceptance.subprocess, "Popen") as launch:
                with self.assertRaisesRegex(ValueError, "wrong target"):
                    acceptance.run(args)
                verify.assert_called_once_with(Path("/fixture/sdk"), args.output / "app.apk",
                                               args.output / "test.apk", args.runner)
                keyboard.assert_not_called()
                launch.assert_not_called()

    def test_default_keeps_independent_release_test_hosts_supported(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            app, test = root / "app-input.apk", root / "test-input.apk"
            app.write_bytes(b"standalone application")
            test.write_bytes(b"independent release test host")
            args = SimpleNamespace(port=5666, apk=app, test_apk=test, runner="independent/Runner",
                                   output=root / "run", clean_install=False, avd="fixture")
            with patch.dict(acceptance.os.environ, {"ANDROID_HOME": "/fixture/sdk"}), \
                    patch.object(acceptance.subprocess, "check_output", return_value="List of devices attached\n"), \
                    patch.object(acceptance.socket, "socket"), \
                    patch.object(acceptance.owned_emulator, "verify_apk_target") as verify, \
                    patch.object(acceptance, "enable_hardware_keyboard", side_effect=RuntimeError("stop before launch")):
                with self.assertRaisesRegex(RuntimeError, "stop before launch"):
                    acceptance.run(args)
                verify.assert_not_called()


if __name__ == "__main__":
    unittest.main()
