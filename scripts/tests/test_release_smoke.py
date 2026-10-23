import importlib.util
import contextlib
import io
import json
from pathlib import Path
import unittest
import tempfile
import sys
from types import SimpleNamespace
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
spec = importlib.util.spec_from_file_location("release_smoke", ROOT / "scripts/run-release-smoke.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class ReleaseSmokeBoundaryTest(unittest.TestCase):
    def test_execution_boundaries_require_a_named_case_and_emulator_before_device_access(self):
        for serial, boundary in (("physical-fixture", "cancel"), ("emulator-5554", None)):
            with self.subTest(serial=serial), tempfile.TemporaryDirectory() as directory:
                arguments = ["run-release-smoke.py", "--serial", serial, "--application-id",
                             "com.helix.validation.smoke.developer", "--suite", "mobile-use-execution",
                             "--apk", "unused.apk", "--test-apk", "unused-test.apk",
                             "--out-dir", str(Path(directory) / "out"),
                             "--adb", "fixture-adb", "--apkanalyzer", "fixture-analyzer"]
                if boundary is not None:
                    arguments += ["--boundary", boundary]
                with patch("sys.argv", arguments), patch.object(runner, "command") as command, \
                        contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                    runner.main()
                command.assert_not_called()

    def test_owned_reinstall_rejects_physical_targets_before_device_access(self):
        with tempfile.TemporaryDirectory() as directory:
            arguments = ["run-release-smoke.py", "--serial", "physical-fixture", "--verify-reinstall",
                         "--application-id", "com.helix.validation.smoke", "--apk", "unused.apk",
                         "--test-apk", "unused-test.apk", "--out-dir", str(Path(directory) / "out"),
                         "--adb", "fixture-adb", "--apkanalyzer", "fixture-analyzer"]
            with patch("sys.argv", arguments), patch.object(runner, "command") as command, \
                    contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                runner.main()
            command.assert_not_called()

    def test_daily_packages_and_shell_fragments_are_rejected(self):
        for value in ("com.helix.agent", "com.helix.agent.developer", "com.helix.validation", "com.helix.validation.x;id"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                runner.isolated_package(value)

    def test_both_isolated_flavors_are_accepted(self):
        for value in ("com.helix.validation.smoke", "com.helix.validation.smoke.developer"):
            self.assertEqual(value, runner.isolated_package(value))

    def test_accessibility_cleanup_preserves_other_services_and_removes_only_owned_service(self):
        service = "com.helix.validation.smoke/OwnedService"
        original = {"enabled_accessibility_services": "daily.app/Original", "accessibility_enabled": "1"}
        calls = []
        def settings(_adb, key, value=None):
            calls.append((key, value))
            if value is None:
                return "daily.app/Original:new.app/New:" + service if len(calls) == 1 else "daily.app/Original:new.app/New"
            return ""
        with patch.object(runner, "secure_setting", side_effect=settings):
            self.assertTrue(runner.restore_accessibility([], service, original))
        self.assertEqual(calls[1], ("enabled_accessibility_services", "daily.app/Original:new.app/New"))
        self.assertNotIn("accessibility_enabled", [key for key, _ in calls])

    def test_accessibility_cleanup_restores_disabled_empty_baseline(self):
        service = "com.helix.validation.smoke/OwnedService"
        original = {"enabled_accessibility_services": "null", "accessibility_enabled": "0"}
        with patch.object(runner, "secure_setting", side_effect=[service, "", "", "null"]) as settings:
            self.assertTrue(runner.restore_accessibility([], service, original))
        self.assertEqual(settings.call_args_list[1].args, ([], "enabled_accessibility_services", "null"))
        self.assertEqual(settings.call_args_list[2].args, ([], "accessibility_enabled", "0"))

    def test_existing_installation_and_wrong_apk_fail_before_device_mutation(self):
        for wrong_apk in (False, True):
            with self.subTest(wrong_apk=wrong_apk), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                apk, test_apk = root / "app.apk", root / "test.apk"
                apk.write_bytes(b"fixture")
                test_apk.write_bytes(b"fixture")
                arguments = ["run-release-smoke.py", "--serial", "fixture", "--application-id",
                             "com.helix.validation.smoke", "--apk", str(apk), "--test-apk", str(test_apk),
                             "--out-dir", str(root / "out"), "--adb", "fixture-adb", "--apkanalyzer", "fixture-analyzer"]

                def read_only_command(command, **_):
                    if "application-id" in command:
                        return "com.helix.agent" if wrong_apk else "com.helix.validation.smoke"
                    if "list" in command:
                        return "package:com.helix.validation.smoke"
                    self.fail(f"Unexpected mutation: {command}")

                with patch("sys.argv", arguments), patch.object(runner, "command", side_effect=read_only_command):
                    with self.assertRaises(ValueError):
                        runner.main()

    def test_release_suite_never_links_debug_or_production_classes(self):
        sources = list((ROOT / "tests/release-smoke/src/androidTest/kotlin").rglob("*.kt"))
        self.assertTrue(sources)
        for path in sources:
            content = path.read_text()
            self.assertNotIn("import com.helix.app", content)
            self.assertNotIn("Class.forName", content)
            self.assertNotIn("clearApplicationUserData", content)

    def test_cleanup_failure_cannot_leave_a_passed_result(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk, host = root / "app.apk", root / "test.apk"
            apk.write_bytes(b"fixture")
            host.write_bytes(b"fixture")
            package = "com.helix.validation.smoke"
            arguments = ["run-release-smoke.py", "--serial", "fixture", "--application-id", package,
                         "--apk", str(apk), "--test-apk", str(host), "--out-dir", str(root / "out"),
                         "--adb", "fixture-adb", "--apkanalyzer", "fixture-analyzer"]

            def command(args, **_):
                if "application-id" in args:
                    return package if args[-1] == str(apk) else "com.helix.validation.releasechecks.test"
                if "debuggable" in args:
                    return "false"
                return "Success"

            result = SimpleNamespace(stdout="fixture", stderr="", check_returncode=lambda: None)
            with patch("sys.argv", arguments), patch.object(runner, "command", side_effect=command), \
                    patch.object(runner, "installed_package", side_effect=[False, False, True, False, True, True]), \
                    patch.object(runner.subprocess, "run", return_value=result), \
                    patch.object(runner, "require_passed", return_value=["fixture"]), \
                    patch.object(runner, "to_xml", return_value="<testsuite/>"):
                with self.assertRaisesRegex(RuntimeError, "cleanup incomplete"):
                    runner.main()
            metadata = json.loads((root / "out/result.json").read_text())
            self.assertEqual("failed", metadata["status"])
            self.assertEqual("CleanupIncomplete", metadata["failure"])
            self.assertFalse(metadata["cleanup"][package])


if __name__ == "__main__":
    unittest.main()
