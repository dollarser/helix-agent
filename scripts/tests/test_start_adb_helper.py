import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("start_adb_helper", Path(__file__).parents[1] / "start-adb-helper.py")
helper = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(helper)


class ComputerHelperProcessTest(unittest.TestCase):
    def setUp(self):
        self.boot = patch.object(helper, "boot_identity", return_value="boot")
        self.boot.start()
        self.addCleanup(self.boot.stop)

    def test_a_previous_boot_never_signals_a_reused_pid_even_with_matching_start_ticks(self):
        with patch.object(helper, "process_start", return_value="old") as process, patch.object(helper, "shell") as shell:
            with self.assertRaisesRegex(RuntimeError, "rebooted"):
                helper.stop_helper(["adb"], {"pid": 123, "startTicks": "old", "bootId": "previous-boot"})
            process.assert_not_called()
            shell.assert_not_called()

    def test_a_receipt_without_execution_identity_never_signals_a_process(self):
        with patch.object(helper, "shell") as shell:
            with self.assertRaisesRegex(RuntimeError, "boot identity"):
                helper.stop_helper(["adb"], {"pid": 123, "startTicks": "old"})
            shell.assert_not_called()

    def test_pid_reuse_never_stops_an_unrelated_process(self):
        with patch.object(helper, "process_start", return_value="new"), patch.object(helper, "shell") as shell:
            with self.assertRaisesRegex(RuntimeError, "PID was reused"):
                helper.stop_helper(["adb"], {"pid": 123, "startTicks": "old", "bootId": "boot"})
            shell.assert_not_called()

    def test_already_exited_helper_is_an_idempotent_stop(self):
        with patch.object(helper, "process_start", return_value=None), patch.object(helper, "shell") as shell:
            helper.stop_helper(["adb"], {"pid": 123, "startTicks": "old", "bootId": "boot"})
            shell.assert_not_called()

    def test_stop_waits_for_the_original_process_to_exit(self):
        with patch.object(helper, "process_start", side_effect=["old", "old", None]), \
                patch.object(helper, "shell") as shell, patch.object(helper.time, "sleep"):
            helper.stop_helper(["adb"], {"pid": 123, "startTicks": "old", "bootId": "boot"})
            shell.assert_called_once_with(["adb"], "kill", "-TERM", "123")

    def test_startup_exception_is_reported_instead_of_waiting_or_success(self):
        result = subprocess.CompletedProcess([], 0, "RuntimeException: cannot connect\n", "")
        with patch.object(helper.subprocess, "run", return_value=result):
            with self.assertRaisesRegex(RuntimeError, "cannot connect"):
                helper.wait_log(["adb"], "/owned/startup.log", "HELIX_ADB_CONNECTED ", 1)

    def test_failed_stop_still_cleans_all_owned_files_without_masking_startup_failure(self):
        original = RuntimeError("original startup failure")
        cleaned = []
        def failed_stop():
            raise TimeoutError("stop unconfirmed")
        with patch.object(helper, "print"):
            with self.assertRaises(RuntimeError) as raised:
                try:
                    raise original
                finally:
                    helper.cleanup_startup([failed_stop, lambda: cleaned.append("grant"), lambda: cleaned.append("input")], original)
        self.assertIs(original, raised.exception)
        self.assertEqual(["grant", "input"], cleaned)

    def test_cleanup_failure_after_connection_is_not_reported_as_full_success(self):
        def failed_cleanup():
            raise OSError("device disconnected")
        with self.assertRaisesRegex(RuntimeError, "cleanup is unconfirmed"):
            helper.cleanup_startup([failed_cleanup], None)
