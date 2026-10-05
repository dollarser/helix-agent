"""Host-only checks. Importing the runner must not invoke adb or a model."""
import importlib.util
import json
import os
import subprocess
from tempfile import TemporaryDirectory
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('capability_runner', Path(__file__).with_name('run-capability-eval.py'))
runner = importlib.util.module_from_spec(spec)
with patch('subprocess.check_output', side_effect=AssertionError('Import must not touch a device')):
    spec.loader.exec_module(runner)


SUCCESS = """INSTRUMENTATION_STATUS: class=com.helix.app.eval.CurrentModelCapabilityDeviceTest
INSTRUMENTATION_STATUS: test=solveOwnedTask
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: class=com.helix.app.eval.CurrentModelCapabilityDeviceTest
INSTRUMENTATION_STATUS: test=solveOwnedTask
INSTRUMENTATION_STATUS_CODE: 0
INSTRUMENTATION_CODE: -1
"""


class CapabilityRunnerTest(unittest.TestCase):
    def evidence(self, **changes):
        return json.dumps(dict(trial='audit', case='gui', session='owned', passed=True,
                               turnState='COMPLETED', failure=None, **changes))

    def test_valid_independent_result(self):
        self.assertTrue(runner.validate_result(self.evidence(), SUCCESS, 'audit', 'gui')['passed'])

    def test_zero_process_exit_does_not_override_instrumentation_failure(self):
        with self.assertRaises(ValueError):
            runner.validate_result(self.evidence(), 'FAILURES!!!\nTests run: 1, Failures: 1', 'audit', 'gui')

    def test_stale_result_identity_is_rejected(self):
        with self.assertRaises(ValueError):
            runner.validate_result(self.evidence(), SUCCESS, 'other', 'gui')

    def test_false_score_failed_turn_and_failure_are_rejected(self):
        for key, value in [('passed', False), ('passed', 'true'), ('turnState', 'FAILED'), ('failure', 'oracle error')]:
            data = json.loads(self.evidence()); data[key] = value
            with self.assertRaises(ValueError):
                runner.validate_result(json.dumps(data), SUCCESS, 'audit', 'gui')

    def test_restore_changes_only_owned_preference(self):
        original = '<map><string name="mobile-use-global-configuration">old&#1;scope</string></map>'
        current = '<map><string name="other">new</string><string name="mobile-use-global-configuration">test</string></map>'
        restored = runner.restore_configuration(current, original)
        self.assertIn('old&#1;scope', restored)
        self.assertIn('<string name="other">new</string>', restored)
        self.assertNotIn('>test<', restored)

    def test_absent_original_configuration_is_removed(self):
        current = '<map><string name="mobile-use-global-configuration">test</string></map>'
        self.assertEqual('', runner.configuration(runner.restore_configuration(current, '<map/>')))

    def test_malformed_document_is_not_overwritten(self):
        with self.assertRaises(ValueError):
            runner.restore_configuration('broken', '<map/>')

    def test_cli_requires_opt_in_and_bounded_unique_inputs(self):
        for argv in [['audit'], ['../escape', '--allow-real-model'],
                     ['audit', 'gui', 'gui', '--allow-real-model'],
                     ['audit', 'unknown', '--allow-real-model'],
                     ['audit', '--allow-real-model', '--serial', 'physical']]:
            with patch('sys.stderr'), self.assertRaises(SystemExit):
                runner.arguments(argv)
        args = runner.arguments(['audit', '--allow-real-model'])
        self.assertEqual(list(runner.CASES), args.cases)

    def test_skipped_or_wrong_test_cannot_count_as_pass(self):
        for log in [SUCCESS.replace('STATUS_CODE: 0', 'STATUS_CODE: -3'),
                    SUCCESS.replace('solveOwnedTask', 'differentTest')]:
            with self.assertRaises(ValueError):
                runner.validate_result(self.evidence(), log, 'audit', 'gui')

    def run_fake(self, timeout=False):
        commands = []
        def output(command, **kwargs):
            commands.append(command)
            if command[-1] == runner.PREFS: return '<map></map>'
            if command[-1].endswith('.json') and 'cat' in command: return self.evidence()
            if 'get' in command: return 'null'
            return ''
        def execute(command, **kwargs):
            if 'instrument' in command:
                if timeout: raise subprocess.TimeoutExpired(command, 310)
                kwargs['stdout'].write(SUCCESS.replace('STATUS_CODE: 0', 'STATUS_CODE: -2'))
        cwd = Path.cwd()
        with TemporaryDirectory(dir=cwd / 'build/current-audit') as temporary:
            try:
                os.chdir(temporary)
                args = runner.arguments(['audit', 'gui', '--allow-real-model'])
                with patch.dict(os.environ, HELIX_EVAL_PROVIDER='test', HELIX_EVAL_MODEL='test'), \
                     patch('subprocess.check_output', side_effect=output), patch('subprocess.run', side_effect=execute):
                    if timeout:
                        with self.assertRaises(subprocess.TimeoutExpired): runner.evaluate(args)
                    else:
                        self.assertEqual(1, runner.evaluate(args))
            finally:
                os.chdir(cwd)
        return commands

    def test_task_failure_exits_nonzero(self):
        self.run_fake()

    def test_timeout_stops_instrumentation_before_restoring_settings(self):
        commands = self.run_fake(timeout=True)
        stop = next(i for i, command in enumerate(commands) if 'force-stop' in command)
        restores = [i for i, command in enumerate(commands) if 'delete' in command and 'settings' in command]
        self.assertEqual(2, len(restores))
        self.assertTrue(all(index > stop for index in restores))


if __name__ == '__main__':
    unittest.main()
