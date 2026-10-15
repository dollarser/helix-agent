"""Host-only evidence contract checks; importing the collector must never start ADB."""
import copy
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
import tempfile
import json

MODULE_PATH = Path(__file__).resolve().parents[1] / 'fixtures/performance/projection-load.py'
spec = importlib.util.spec_from_file_location('projection_load_evidence', MODULE_PATH)
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)


class ProjectionEvidenceTest(unittest.TestCase):
    def report(self):
        return dict(fixtureSessions=600, fixtureMessages=1000, ordinaryBodyBytes=1024,
                    directoryMs=[0, 100, 250, 2, 1], conversationMs=[1, 2, 3, 4, 5],
                    searchMsIncludingDebounce=[210, 205, 220, 201, 208])

    def test_valid_samples_preserve_values_without_a_speed_threshold(self):
        report = self.report()
        report['conversationMs'][-1] = 20_000
        self.assertIs(collector.validate_report(report), report)

    def test_missing_fields_or_wrong_fixture_counts_fail(self):
        for change in ('missing', 'count', 'extra'):
            report = self.report()
            if change == 'missing':
                del report['directoryMs']
            elif change == 'count':
                report['fixtureMessages'] = 999
            else:
                report['untrustedExtra'] = 'not a metric'
            with self.subTest(change=change), self.assertRaises(AssertionError):
                collector.validate_report(report)

    def test_empty_partial_negative_and_noninteger_samples_fail(self):
        for value in ([], [1], [0, 1, 2, 3, -1], [0, 1, 2, 3, True], [0, 1, 2, 3, 0.5]):
            report = copy.deepcopy(self.report())
            report['directoryMs'] = value
            with self.subTest(value=value), self.assertRaises(AssertionError):
                collector.validate_report(report)

    def test_cold_start_requires_fresh_pid_and_observed_total_time(self):
        raw = 'Status: ok\nLaunchState: COLD\nTotalTime: 125\nWaitTime: 130\n'
        self.assertEqual(dict(TotalTime=125, WaitTime=130, beforePid=10, afterPid=20),
                         collector.parse_cold_start(raw, 10, 20))
        for before, after in ((10, 10), (0, 20), (True, 20)):
            with self.subTest(before=before, after=after), self.assertRaises(AssertionError):
                collector.parse_cold_start(raw, before, after)

    def test_hot_missing_or_failed_launch_is_never_a_cold_sample(self):
        for raw in ('Status: ok\nLaunchState: HOT\nTotalTime: 1\n',
                    'Status: error\nLaunchState: COLD\nTotalTime: 1\n',
                    'Status: ok\nLaunchState: COLD\n',
                    'Status: ok\nTotalTime: 1\n'):
            with self.subTest(raw=raw), self.assertRaises(AssertionError):
                collector.parse_cold_start(raw, 10, 20)

    def test_other_serial_is_rejected_before_any_adb_action(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, 'owner.json').write_text(json.dumps(dict(serial='emulator-5554')))
            with patch.object(collector.subprocess, 'check_output') as adb:
                with self.assertRaises(AssertionError):
                    collector.collect('emulator-5680', directory)
                adb.assert_not_called()

    def test_dead_owned_process_is_rejected_before_any_adb_action(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, 'owner.json').write_text(json.dumps(dict(serial='emulator-5680', pid=123, avd='Owned')))
            with patch.dict(collector.os.environ, FIXTURE_PACKAGE='com.helix.agent', ANDROID_HOME='/sdk'), \
                    patch.object(collector.os, 'kill', side_effect=ProcessLookupError), \
                    patch.object(collector.subprocess, 'check_output') as adb:
                with self.assertRaises(ProcessLookupError):
                    collector.collect('emulator-5680', directory)
                adb.assert_not_called()


if __name__ == '__main__':
    unittest.main()
