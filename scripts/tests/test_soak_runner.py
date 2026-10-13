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
    def test_live_mobile_workload_is_explicit_and_cannot_replace_resource_inputs(self):
        identity = runner.workload_identity('mobile-model', 'developer')
        self.assertEqual(identity['measure_pkg'], 'com.helix.agent.developer')
        self.assertIn('ContinuousMobileModelResourceDeviceTest', identity['test'])
        with self.assertRaises(ValueError):
            runner.workload_identity('mobile-model', 'consumer')
        self.assertEqual(runner.instrument_arguments(['pluginSkillPort=30008', 'pluginSkillModel=Qwen3.8-27B']),
                         ['-e', 'pluginSkillPort', '30008', '-e', 'pluginSkillModel', 'Qwen3.8-27B'])
        for args in (['helix.soak.observe=true'], ['class=other'], ['pluginSkillPort=0'],
                     ['pluginSkillModel=bad;command'], ['pluginSkillModel=x', 'pluginSkillModel=y']):
            with self.subTest(args=args), self.assertRaises(ValueError):
                runner.instrument_arguments(args)

    def green(self, **changes):
        facts = dict(instStream='OK (1 test)', instVerified=True, fixturePids={12}, hostPids={12},
                     testRunnerFinished=True, fixtureElapsedMs=7200000, seconds=7200,
                     elapsedSec=7250, preset='precheck')
        return {**facts, **changes}

    def test_artifact_preflight_only_reads_paths_and_hashes(self):
        soak = runner.Soak.__new__(runner.Soak)
        soak.shell_ok = Mock(side_effect=['package:/owned/base.apk', 'abc123 /owned/base.apk'])
        self.assertEqual({'path': '/owned/base.apk', 'sha256': 'abc123'}, soak._installed_artifact('owned'))

    def test_sample_preserves_actual_memory_categories(self):
        with tempfile.TemporaryDirectory() as directory:
            soak = runner.Soak.__new__(runner.Soak)
            soak.out = Path(directory)
            soak.started = runner.time.monotonic()
            soak.samples = []
            soak.host_pids = set()
            soak.memory_sampler = 'full'
            soak.wl = runner.workload_identity('app')
            soak.target_pid = Mock(return_value=12)
            raw = 'Native Heap 100\nDalvik Heap 200\n TOTAL 300\n'
            soak.shell_ok = Mock(return_value=raw)
            soak.sample()
            record = json.loads((soak.out/'samples.jsonl').read_text())
            self.assertEqual(300, record['appPssKb'])
            self.assertEqual(raw, (soak.out/record['meminfoRef']).read_text())

    def test_green_summary_requires_completed_unskipped_method_and_device_duration(self):
        self.assertEqual('PASS', runner.classify(self.green())[0])
        for changes in ({'instVerified': False}, {'testRunnerFinished': False},
                        {'fixtureElapsedMs': 7199999}, {'fixtureElapsedMs': None}):
            with self.subTest(changes=changes):
                self.assertEqual('INCONCLUSIVE', runner.classify(self.green(**changes))[0])

    def test_conflicting_host_process_is_not_hidden_by_fixture_identity(self):
        self.assertEqual('FAIL_FUNCTIONAL', runner.classify(self.green(hostPids={13}))[0])

    def test_detached_launch_preserves_explicit_target_and_closes_terminal_descriptors(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(runner.subprocess, 'Popen') as popen:
            popen.return_value.pid = 42
            out = Path(directory)/'new-run'
            owner = runner.launch_detached(['owned-phone', '--physical-device', '--detach', '--output', str(out)], out)
            args, kwargs = popen.call_args
            self.assertIn('owned-phone', args[0])
            self.assertIn('--physical-device', args[0])
            self.assertNotIn('--detach', args[0])
            self.assertIn('--detached-worker', args[0])
            self.assertTrue(kwargs['start_new_session'])
            self.assertTrue(kwargs['close_fds'])
            self.assertEqual(runner.subprocess.DEVNULL, kwargs['stdin'])
            self.assertEqual(42, owner['pid'])
            with self.assertRaises(FileExistsError):
                runner.launch_detached([], out)

    def test_physical_device_is_explicit_and_never_an_emulator_alias(self):
        runner.validate_device_serial('emulator-5668')
        runner.validate_device_serial('owned-phone', physical=True)
        for serial, physical in [('owned-phone', False), ('emulator-5668', True), ('', True), ('phone;cmd', True)]:
            with self.subTest(serial=serial, physical=physical), self.assertRaises(ValueError):
                runner.validate_device_serial(serial, physical)

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
            soak.wl = runner.workload_identity('app')
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

    def test_binary_log_preserves_valid_evidence_and_reports_corruption(self):
        with tempfile.TemporaryDirectory() as directory:
            soak = runner.Soak.__new__(runner.Soak)
            soak.out = Path(directory)
            (soak.out / 'logcat.log').write_bytes(
                b'Other: invalid \xbe\nHelixSoak: pid=12 cycles=1 elapsedMs=900000 fd=4 threads=5 pssKb=6\n'
                b'TestRunner: run finished: 1 tests, 0 failed, 0 ignored\n')
            facts = soak.scan_logcat()
            self.assertEqual(1, facts['invalidUtf8Lines'])
            self.assertEqual(0, facts['invalidUtf8FixtureLines'])
            self.assertEqual({12}, facts['fixturePids'])
            self.assertTrue(facts['testRunnerFinished'])
            (soak.out / 'logcat.log').write_bytes(b'TestRunner: invalid \xbe\n')
            self.assertEqual(1, soak.scan_logcat()['invalidUtf8FixtureLines'])

    def test_finalize_failure_is_persisted_without_claiming_device_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            soak = runner.Soak.__new__(runner.Soak)
            soak.out = Path(directory)
            soak.identity = {'runId': 'owned'}
            soak._finalize = Mock(side_effect=ValueError('malformed evidence'))
            self.assertEqual(1, soak.finalize(900, None, soak.out / 'instrumentation.log'))
            result = json.loads((soak.out / 'result.json').read_text())
            self.assertEqual('INCONCLUSIVE', result['state'])
            self.assertEqual('report finalization', result['attribution'])
            self.assertEqual('ValueError', result['finalizationError'])

    def test_corrupt_fixture_evidence_cannot_pass_finalization(self):
        with tempfile.TemporaryDirectory() as directory:
            soak = runner.Soak.__new__(runner.Soak)
            soak.out = Path(directory)
            soak.identity = {}
            soak.args = SimpleNamespace(workload='app', preset='pilot')
            soak.wl = runner.workload_identity('app')
            soak.seconds = 900
            soak.cancelled = False
            soak.samples = []
            soak.host_pids = {12}
            (soak.out / 'logcat.log').write_bytes(
                b'HelixSoak: pid=12 cycles=1 elapsedMs=900000 fd=4 threads=5 pssKb=6\n'
                b'TestRunner: run finished: 1 tests, 0 failed, 0 ignored \xbe\n')
            path = soak.out / 'instrumentation.log'
            path.write_text('OK (1 test)')
            method = tuple(soak.wl['test'].split('#'))
            with patch.object(runner, 'require_passed', return_value=[method]):
                self.assertEqual(1, soak.finalize(905, None, path))
            result = json.loads((soak.out / 'result.json').read_text())
            self.assertEqual('INCONCLUSIVE', result['state'])
            self.assertEqual(1, result['logDecoding']['invalidUtf8FixtureLines'])


if __name__ == '__main__':
    unittest.main()
