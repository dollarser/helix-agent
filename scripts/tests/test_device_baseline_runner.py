import fcntl
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DEBUG_DIR = ROOT / "scripts" / "debug" / "2026-09-26"


def load_script(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, DEBUG_DIR / filename)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


summarizer = load_script("device_baseline_summarizer", "summarize-current-device-baseline.py")
runner = load_script("device_baseline_runner", "run-isolated.py")


class DeviceBaselineClassificationTest(unittest.TestCase):
    def test_all_sweep_recovery_classes_require_their_host_driver(self):
        classes = (
            'chat.SessionInputProcessRecoveryDeviceTest', 'connector.ConnectorInstallRecoveryDeviceTest',
            'export.SessionExportRecoveryDeviceTest', 'ui.SharedStorageDeviceTest',
            'ui.ManualSharedFileDeviceTest', 'chat.MemoryProcessRecoveryDeviceTest',
            'files.WorkspaceBackupRecoveryDeviceTest', 'localmodel.ModelPublicationRecoveryDeviceTest',
            'localmodel.FirstSuccessJourneyDeviceTest', 'chat.ComposerProcessRecoveryDeviceTest',
            'chat.WorkspaceProcessRecoveryDeviceTest', 'ui.MessageEditRecoveryDeviceTest',
        )
        for suffix in classes:
            name = 'com.helix.app.' + suffix
            with self.subTest(name=name):
                self.assertIn(name, runner.KNOWN_PHASE_RUNNER_CLASSES)
                self.assertEqual('PHASE_RUNNER_REQUIRED', summarizer.classify_result(name, 'PHASE_RUNNER_REQUIRED', ''))

    def test_recovery_driver_registration_does_not_hide_actual_failures(self):
        for suffix in ('chat.MemoryProcessRecoveryDeviceTest', 'localmodel.ModelPublicationRecoveryDeviceTest',
                       'localmodel.FirstSuccessJourneyDeviceTest'):
            name = 'com.helix.app.' + suffix
            self.assertEqual('NEW_REGRESSION', summarizer.classify_result(name, 'FAIL', 'a new product failure'))

    def test_soak_requires_its_authoritative_host_verifier(self):
        self.assertIn("com.helix.app.MainAppCombinedSoakDeviceTest", runner.KNOWN_PHASE_RUNNER_CLASSES)

    def test_timeout_preserves_partial_bytes_as_text_and_cannot_pass(self):
        failure = subprocess.TimeoutExpired(["fixture"], 1, output=b"partial\xff", stderr=b"diagnostic")
        with patch.object(runner.subprocess, "run", side_effect=failure):
            code, out, err = runner.run_cmd(["fixture"], timeout=1)
        self.assertEqual(-999, code)
        self.assertEqual("partial\ufffd", out)
        self.assertIn("diagnostic", err)
        self.assertEqual("NO_VERDICT / PROCESS_CRASH", runner.parse_instrumentation_log("fixture", out, code)[0])

    def test_exact_known_failure_signature_is_preserved(self):
        log = """
java.lang.AssertionError: production state must settle
FAILURES!!!
Tests run: 3,  Failures: 1
"""
        self.assertEqual(
            "KNOWN_EXISTING_FAILURE",
            summarizer.classify_result("com.helix.app.ApprovalFlowDeviceTest", "FAIL", log),
        )

    def test_known_class_with_new_failure_is_a_regression(self):
        changed_count = """
java.lang.AssertionError: production state must settle
FAILURES!!!
Tests run: 3,  Failures: 2
"""
        changed_reason = """
java.lang.AssertionError: a new invariant failed
FAILURES!!!
Tests run: 3,  Failures: 1
"""
        for log in (changed_count, changed_reason):
            self.assertEqual(
                "NEW_REGRESSION",
                summarizer.classify_result("com.helix.app.ApprovalFlowDeviceTest", "FAIL", log),
            )

    def test_environment_limit_requires_exact_signature(self):
        historical = """
java.lang.AssertionError: Failed to inject touch input.
FAILURES!!!
Tests run: 4,  Failures: 4
"""
        different = """
java.lang.AssertionError: product state changed unexpectedly
FAILURES!!!
Tests run: 4,  Failures: 4
"""
        cls = "com.helix.app.ui.ProductFileJourneyDeviceTest"
        self.assertEqual("ENVIRONMENT_LIMITATION", summarizer.classify_result(cls, "FAIL", historical))
        self.assertEqual("NEW_REGRESSION", summarizer.classify_result(cls, "FAIL", different))

    def test_phase_skip_and_process_crash_are_never_reclassified(self):
        self.assertEqual(
            "PHASE_RUNNER_REQUIRED",
            summarizer.classify_result(
                "com.helix.app.chat.ComposerProcessRecoveryDeviceTest",
                "PHASE_RUNNER_REQUIRED",
                "",
            ),
        )
        self.assertEqual(
            "NO_VERDICT / PROCESS_CRASH",
            summarizer.classify_result(
                "com.helix.app.ApprovalFlowDeviceTest",
                "NO_VERDICT / PROCESS_CRASH",
                "production state must settle",
            ),
        )


def status_event(code, cls='com.helix.app.Fixture', method='method'):
    return (f'INSTRUMENTATION_STATUS: class={cls}\n'
            f'INSTRUMENTATION_STATUS: test={method}\n'
            f'INSTRUMENTATION_STATUS_CODE: {code}\n')


def completed_log(code=0, cls='com.helix.app.Fixture', method='method'):
    start = '' if code == -3 else status_event(1, cls, method)
    summary = 'FAILURES!!!\nTests run: 1,  Failures: 1\n' if code in (-1, -2) else 'OK (1 test)\n'
    return start + status_event(code, cls, method) + summary + 'INSTRUMENTATION_CODE: -1\n'


class DeviceBaselineVerdictTest(unittest.TestCase):
    def verdict(self, raw, code=0, cls='com.helix.app.Fixture'):
        return runner.parse_instrumentation_log(cls, raw, code)[0]

    def test_pass_requires_complete_status_evidence(self):
        self.assertEqual('PASS', self.verdict(completed_log()))
        for raw in ('OK (1 test)\n', 'OK (0 tests)\n', status_event(1) + 'OK (1 test)\n'):
            with self.subTest(raw=raw):
                self.assertEqual('UNRESOLVED', self.verdict(raw))

    def test_nonzero_exit_never_passes_even_with_success_text(self):
        for code in (1, -9, -999):
            with self.subTest(code=code):
                self.assertEqual('NO_VERDICT / PROCESS_CRASH', self.verdict(completed_log(), code))

    def test_phase_keywords_do_not_hide_real_failures(self):
        for name in ('com.helix.app.Fixture', 'com.helix.app.chat.MemoryProcessRecoveryDeviceTest'):
            raw = completed_log(-2, cls=name) + 'recoveryPhase: Use the two-phase owned runner\n'
            with self.subTest(name=name):
                self.assertEqual('FAIL', self.verdict(raw, cls=name))
                self.assertEqual('NEW_REGRESSION', summarizer.classify_result(name, self.verdict(raw, cls=name), raw))

    def test_phase_word_in_successful_test_name_is_not_a_driver_requirement(self):
        self.assertEqual('PASS', self.verdict(completed_log(method='recoveryPhaseDoesNotAuthorizeExecution')))

    def test_failure_has_precedence_over_an_ok_banner(self):
        self.assertEqual('FAIL', self.verdict(completed_log(-2) + 'OK (1 test)\n'))

    def test_status_failure_without_failure_banner_is_not_a_pass(self):
        raw = status_event(1) + status_event(-2) + 'OK (1 test)\nINSTRUMENTATION_CODE: -1\n'
        self.assertEqual('FAIL', self.verdict(raw))

    def test_skips_and_partial_skips_are_preserved(self):
        for code in (-3, -4):
            self.assertEqual('SKIP / ASSUMPTION', self.verdict(completed_log(code)))
        raw = (status_event(1) + status_event(0) + status_event(-3, method='ignored') +
               'OK (1 test)\nINSTRUMENTATION_CODE: -1\n')
        verdict, detail = runner.parse_instrumentation_log('com.helix.app.Fixture', raw, 0)
        self.assertEqual('PASS', verdict)
        self.assertIn('1 skipped', detail)

    def test_wrong_test_identity_does_not_validate_requested_class(self):
        self.assertEqual('UNRESOLVED', self.verdict(completed_log(cls='com.helix.app.Other')))
        self.assertEqual('UNRESOLVED', self.verdict(completed_log(), cls='com.helix.app.Fixture#otherMethod'))

    def test_duplicate_or_unfinished_status_cannot_pass(self):
        for raw in (completed_log() + status_event(0), completed_log() + status_event(1, method='unfinished')):
            self.assertEqual('UNRESOLVED', self.verdict(raw))

    def test_missing_or_conflicting_runner_termination_cannot_pass(self):
        for raw in (completed_log().replace('INSTRUMENTATION_CODE: -1\n', ''),
                    completed_log() + 'INSTRUMENTATION_CODE: 0\n'):
            self.assertEqual('UNRESOLVED', self.verdict(raw))

    def test_health_probe_checks_exit_identity_and_actual_pass_not_a_skip(self):
        cls, method = runner.HEALTH_PROBE_CLASS.split('#')
        cases = [(0, completed_log(cls=cls, method=method), True),
                 (1, completed_log(cls=cls, method=method), False),
                 (-999, completed_log(cls=cls, method=method), False),
                 (0, completed_log(-4, cls=cls, method=method), False),
                 (0, completed_log(), False), (0, 'OK (1 test)\n', False)]
        for code, raw, expected in cases:
            with self.subTest(code=code, raw=raw), patch.object(runner, 'run_cmd', return_value=(code, raw, '')):
                self.assertEqual(expected, runner.run_health_probe('fixture-serial', 'fixture-runner'))


class DeviceBaselineSummaryIntegrityTest(unittest.TestCase):
    def assert_invalid_evidence_preserves_previous_summary(self, manifest=None, result=None, missing_manifest=False):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'results').mkdir()
            previous = root / 'summary.json'
            previous.write_text('previous verified report')
            expected = root / 'manifest.json'
            if manifest is not None:
                expected.write_text(json.dumps(manifest))
            if result is not None:
                (root / 'results' / 'fixture.json').write_text(result)
            with self.assertRaises((ValueError, FileNotFoundError)):
                summarizer.summarize(str(root), str(expected) if manifest is not None or missing_manifest else None)
            self.assertEqual('previous verified report', previous.read_text())

    def test_missing_requested_manifest_is_not_treated_as_no_manifest(self):
        self.assert_invalid_evidence_preserves_previous_summary(missing_manifest=True)

    def test_manifest_requires_a_real_class_list(self):
        for value in ({}, {'classes': 'com.helix.app.Fixture'}, {'classes': [None]}, {'classes': ['']}):
            with self.subTest(value=value):
                self.assert_invalid_evidence_preserves_previous_summary(manifest=value)

    def test_duplicate_manifest_classes_are_not_silently_deduplicated(self):
        self.assert_invalid_evidence_preserves_previous_summary(manifest={'classes': ['Fixture', 'Fixture']})

    def test_corrupt_result_cannot_be_silently_omitted_from_report(self):
        self.assert_invalid_evidence_preserves_previous_summary(result='{broken')

    def test_result_requires_an_object_with_class_identity(self):
        for value in ({}, {'class': ''}, {'class': 12}, []):
            with self.subTest(value=value):
                self.assert_invalid_evidence_preserves_previous_summary(result=json.dumps(value))


class DeviceBaselineLockTest(unittest.TestCase):
    def test_lock_is_exclusive_across_processes(self):
        with tempfile.TemporaryDirectory() as tmp:
            lock_path = str(Path(tmp) / ".runner.lock")
            fd = runner.acquire_single_writer_lock(
                lock_path,
                {"pid": 1, "run_id": "parent", "started_at": "test"},
            )
            child_code = f"""
import importlib.util
import sys
spec = importlib.util.spec_from_file_location("child_runner", {str(DEBUG_DIR / "run-isolated.py")!r})
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)
try:
    mod.acquire_single_writer_lock(sys.argv[1], {{"pid": 2, "run_id": "child", "started_at": "test"}})
except RuntimeError:
    raise SystemExit(0)
raise SystemExit(1)
"""
            try:
                blocked = subprocess.run(
                    [sys.executable, "-c", child_code, lock_path],
                    check=False,
                    capture_output=True,
                    text=True,
                )
                self.assertEqual(0, blocked.returncode, blocked.stderr)
            finally:
                fcntl.flock(fd, fcntl.LOCK_UN)
                Path(lock_path).touch(exist_ok=True)
                try:
                    import os

                    os.close(fd)
                except OSError:
                    pass

            acquired = subprocess.run(
                [sys.executable, "-c", child_code, lock_path],
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(1, acquired.returncode, acquired.stderr)


if __name__ == "__main__":
    unittest.main()
