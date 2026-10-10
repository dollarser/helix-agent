"""Host-only channel ownership and terminal-result regression for the existing soak runner."""
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('soak_runner', Path(__file__).resolve().parents[1] / 'run-hxa103-browser-soak.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class SoakRunnerTest(unittest.TestCase):
    def test_default_channel_keeps_existing_app_identity(self):
        self.assertEqual(runner.workload_identity('app'), runner.WORKLOADS['app'])

    def test_developer_uses_its_own_app_and_runner_without_mutating_defaults(self):
        identity = runner.workload_identity('app', 'developer')
        self.assertEqual(identity['measure_pkg'], 'com.helix.agent.developer')
        self.assertEqual(identity['component'], 'com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner')
        self.assertEqual(runner.WORKLOADS['app']['measure_pkg'], 'com.helix.agent')

    def test_invalid_flavor_and_misattributed_library_fail_before_adb(self):
        for workload, flavor in [('app', 'invalid'), ('browser', 'developer')]:
            with self.subTest(workload=workload, flavor=flavor), self.assertRaises(ValueError):
                runner.workload_identity(workload, flavor)

    def test_library_identity_remains_independent(self):
        self.assertEqual(runner.workload_identity('browser'), runner.WORKLOADS['browser'])

    def test_report_reads_actual_completed_cycle_sizes(self):
        with tempfile.TemporaryDirectory() as directory:
            soak = runner.Soak.__new__(runner.Soak)
            soak.out = Path(directory)
            (soak.out / 'logcat.log').write_text(
                'HelixSoakCases: pid=4000 cases=8\nHelixSoakCases: pid=4000 cases=11\n'
                'TestRunner: run finished: 2 tests, 0 failed, 0 ignored\n')
            facts = soak.scan_logcat()
            self.assertEqual(facts['nestedCaseCounts'], [8, 11])
            self.assertTrue(facts['testRunnerFinished'])

    def test_failed_warmup_does_not_claim_completed_nested_cases(self):
        with tempfile.TemporaryDirectory() as directory:
            soak = runner.Soak.__new__(runner.Soak)
            soak.out = Path(directory)
            soak.args = SimpleNamespace(workload='app', preset='pilot')
            soak.seconds = 900
            soak.cancelled = False
            soak.identity = {}
            soak.samples = []
            soak.host_pids = set()
            soak.scan_logcat = Mock(return_value={
                'fixturePids': set(), 'cycles': None, 'fixturePeakFd': None,
                'fixturePeakThreads': None, 'fixturePeakPssKb': None,
                'testRunnerFinished': False, 'failureMsgs': [],
            })
            path = soak.out / 'instrumentation.log'
            path.write_text('FAILURES!!!\nTests run: 1, Failures: 1')
            self.assertEqual(soak.finalize(30, None, path), 1)
            result = json.loads((soak.out / 'result.json').read_text())
            self.assertEqual(result['state'], 'FAIL_FUNCTIONAL')
            self.assertEqual(result['totalNestedCases'], 0)
            self.assertEqual(result['baselineWarmupCycles'], 0)

    def test_run_propagates_terminal_failure_and_success(self):
        # Exercise run(), not merely classify(): previously every failed result returned zero.
        for code in (0, 1):
            with self.subTest(code=code), tempfile.TemporaryDirectory() as directory:
                soak = runner.Soak.__new__(runner.Soak)
                soak.out = Path(directory)
                soak.base = ['adb', '-s', 'emulator-5668']
                soak.wl = runner.workload_identity('app')
                soak.seconds = 900
                soak.observe = False
                soak.identity = {}
                soak.build_identity = Mock()
                soak.finalize = Mock(return_value=code)
                collector = Mock()
                instrument = Mock(poll=Mock(return_value=0))
                with patch.object(runner.subprocess, 'Popen', side_effect=[collector, instrument]):
                    self.assertEqual(soak.run(), code)
                collector.terminate.assert_called_once()
                collector.wait.assert_called_once_with(timeout=15)
                soak.finalize.assert_called_once()


if __name__ == '__main__':
    unittest.main()
