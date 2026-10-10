"""Host-only lifecycle and loss regression for the existing owned runner."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('owned_runner_log', Path(__file__).resolve().parents[1] / 'run-owned-emulator.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class LogCaptureTest(unittest.TestCase):
    def process(self):
        return Mock(pid=123, returncode=-15, poll=Mock(return_value=None))

    def test_early_late_and_non_utf8_evidence_survive_more_than_2000_lines(self):
        content = b'EARLY\n' + b'fixture\n' * 2500 + b'\xffLATE\n'
        process = self.process()
        def launch(command, **kwargs):
            self.assertNotIn('-t', command)
            self.assertEqual(command[:4], ['adb', '-s', 'emulator-5670', 'logcat'])
            kwargs['stdout'].write(content)
            return process
        with tempfile.TemporaryDirectory() as directory, patch.object(runner.subprocess, 'Popen', side_effect=launch), patch.object(runner.time, 'sleep'):
            root = Path(directory)
            with runner.capture_test_logcat('adb', 'emulator-5670', root, lambda: True):
                pass
            self.assertEqual((root / 'test-logcat.txt').read_bytes(), content)
            self.assertEqual(json.loads((root / 'logcat-capture.json').read_text())['pid'], 123)
        process.terminate.assert_called_once()
        process.wait.assert_called_once_with(timeout=10)

    def test_dead_owner_rejected_before_launch(self):
        with patch.object(runner.subprocess, 'Popen') as launch:
            with self.assertRaisesRegex(RuntimeError, 'exited before'):
                with runner.capture_test_logcat('adb', 'emulator-5670', Path('/unused'), lambda: False):
                    pass
            launch.assert_not_called()

    def test_early_capture_exit_is_not_a_pass(self):
        process = self.process()
        process.poll.return_value = 1
        process.returncode = 1
        with tempfile.TemporaryDirectory() as directory, patch.object(runner.subprocess, 'Popen', return_value=process), patch.object(runner.time, 'sleep'):
            with self.assertRaisesRegex(RuntimeError, 'ended before'):
                with runner.capture_test_logcat('adb', 'emulator-5670', Path(directory), lambda: True):
                    pass
        process.terminate.assert_not_called()

    def test_timeout_and_cancellation_preserve_original_error_and_close_capture(self):
        for error in (subprocess.TimeoutExpired('instrumentation', 1), KeyboardInterrupt()):
            process = self.process()
            with self.subTest(error=type(error).__name__), tempfile.TemporaryDirectory() as directory, patch.object(runner.subprocess, 'Popen', return_value=process):
                with self.assertRaises(type(error)):
                    with runner.capture_test_logcat('adb', 'emulator-5670', Path(directory), lambda: True):
                        raise error
                self.assertTrue((Path(directory) / 'logcat-capture.json').is_file())
            process.terminate.assert_called_once()

    def test_reader_that_does_not_stop_is_killed_by_its_own_handle(self):
        process = self.process()
        process.wait.side_effect = [subprocess.TimeoutExpired('logcat', 10), 0]
        with tempfile.TemporaryDirectory() as directory, patch.object(runner.subprocess, 'Popen', return_value=process), patch.object(runner.time, 'sleep'):
            with runner.capture_test_logcat('adb', 'emulator-5670', Path(directory), lambda: True):
                pass
        process.kill.assert_called_once()
        self.assertEqual(process.wait.call_count, 2)
