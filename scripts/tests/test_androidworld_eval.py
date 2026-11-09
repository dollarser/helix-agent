import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("androidworld_eval", Path(__file__).parents[1] / "androidworld_eval.py")
eval = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(eval)


class AccountingTest(unittest.TestCase):
    def setUp(self):
        self.expected = dict(runId="run", caseId="case", task="Task", attempt=1, goal="synthetic")
        self.evidence = dict(self.expected, turnState="COMPLETED", failure=None, status="EXECUTED")

    def classify(self, **kwargs):
        args = dict(expected=self.expected, evidence=self.evidence, initial_oracle=0.0,
                    final_oracle=1.0, instrumentation_ok=True)
        args.update(kwargs)
        return eval.classify(**args)

    def test_invalid_initial_oracle_never_invokes_agent(self):
        for value in (None, True, float("nan"), float("inf"), -1, 2):
            calls = []
            result = eval.execute_case(self.expected, lambda: None, lambda: value,
                                       lambda: calls.append("agent"),
                                       lambda: calls.append("cleanup"))
            self.assertEqual(calls, ["cleanup"])
            self.assertEqual(result["status"], "ERROR")

    def test_evidence_write_failures_never_pass(self):
        self.evidence["evidenceErrors"] = ["progress could not be persisted"]
        self.assertEqual(self.classify()["status"], "ERROR")

    def test_atomic_failed_write_preserves_complete_checkpoint(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "checkpoint.json"
            eval.atomic_json(path, {"state": "original"})
            original = path.read_bytes()
            with self.assertRaises(ValueError):
                eval.atomic_json(path, {"bad": float("nan")})
            self.assertEqual(path.read_bytes(), original)
            self.assertEqual(list(Path(directory).iterdir()), [path])

    def test_error_reporting_is_bounded_and_does_not_call_str(self):
        class BrokenError(Exception):
            def __str__(self):
                raise AssertionError("unsafe exception formatter")
        self.assertLess(len(eval.bounded_error(BrokenError("x" * 10000))), 2100)

    def test_only_canonical_executed_status_can_pass(self):
        for status in (None, "", "ERROR", "COMPLETED", "WAITING", "UNKNOWN"):
            self.assertEqual(self.classify(evidence=dict(self.evidence, status=status))["status"], "ERROR")
        self.assertEqual(self.classify(instrumentation_ok=1)["status"], "ERROR")
        self.assertEqual(self.classify(evidence=None)["status"], "ERROR")

    def test_attempt_identity_rejects_boolean_and_float_aliases(self):
        for attempt in (True, 1.0, "1"):
            self.assertEqual(self.classify(evidence=dict(self.evidence, attempt=attempt))["status"], "ERROR")

    def test_pass_requires_oracle_and_turn(self):
        self.assertEqual(self.classify()["status"], "PASS")
        self.assertEqual(self.classify(final_oracle=0.0)["status"], "FAIL")
        self.evidence["turnState"] = "FAILED"
        result = self.classify()
        self.assertEqual(result["status"], "FAIL")
        self.assertTrue(result["oracle_success"])
        self.assertFalse(result["turn_completed"])

    def test_identity_prevents_stale_evidence(self):
        for key in self.expected:
            evidence = dict(self.evidence, **{key: "wrong"})
            self.assertEqual(self.classify(evidence=evidence)["status"], "ERROR")

    def test_fixture_failures_never_pass(self):
        for kwargs in (dict(initial_oracle=1.0), dict(final_oracle=None),
                       dict(instrumentation_ok=False), dict(cleanup_error="failed"),
                       dict(evidence=dict(self.evidence, cleanupErrors=["stop failed"]))):
            self.assertEqual(self.classify(**kwargs)["status"], "ERROR")
        self.evidence["status"] = "BLOCKED"
        self.assertEqual(self.classify()["status"], "BLOCKED")

    def test_resume_validates_identity_and_keeps_denominator(self):
        manifest = dict(runId="run", cases=[dict(caseId="case"), dict(caseId="other")],
                        sourceSha256="source", apkSha256="apk", testApkSha256="test",
                        datasetRevision="revision", datasetSha256="data", model="model", seed=30)
        with tempfile.TemporaryDirectory() as directory:
            ledger = eval.Ledger(directory, manifest)
            ledger.record(self.classify())
            (Path(directory) / "checkpoint.json").write_text('{}')
            summary = eval.Ledger(directory, manifest).summary()
            self.assertEqual(summary["denominator"], 2)
            self.assertEqual(summary["valid_coverage"], 0.5)
            self.assertEqual(summary["status"], "incomplete")
            with self.assertRaises(ValueError):
                ledger.record(self.classify())
            with self.assertRaises(ValueError):
                eval.Ledger(directory, dict(manifest, seed=31))

    def test_cleanup_runs_after_partial_initialization(self):
        calls = []
        def fail():
            raise RuntimeError("partial initialize")
        result = eval.execute_case(self.expected, fail, lambda: 0, lambda: None,
                                   lambda: calls.append("cleanup"))
        self.assertEqual(calls, ["cleanup"])
        self.assertEqual(result["status"], "ERROR")

    def test_cleanup_failure_invalidates_success(self):
        scores = iter([0.0, 1.0])
        def fail():
            raise RuntimeError("teardown")
        result = eval.execute_case(self.expected, lambda: None, lambda: next(scores),
                                   lambda: (self.evidence, True), fail)
        self.assertEqual(result["status"], "ERROR")
        self.assertTrue(result["oracle_success"])

    def test_official_partial_scores_and_verification_tasks(self):
        self.assertEqual(self.classify(final_oracle=0.5)["status"], "FAIL")
        self.assertEqual(self.classify(final_oracle=float("nan"))["status"], "ERROR")
        expected = dict(self.expected, allowsInitiallySatisfied=True)
        self.assertEqual(self.classify(expected=expected, initial_oracle=1.0)["status"], "PASS")

    def test_blocked_is_not_complete(self):
        self.evidence["status"] = "BLOCKED"
        self.assertFalse(self.classify()["status"] == "PASS")


if __name__ == "__main__":
    unittest.main()
