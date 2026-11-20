import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("real_tasks", ROOT / "scripts/device_baseline/real_tasks.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class RealTaskBaselineTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.identity = {"providerId": "builtin", "modelId": "candidate"}
        self.manifest = {"modelIdentity": self.identity, "cases": [{"id": name} for name in ("file", "log", "web", "session")]}
        self.write("manifest.json", self.manifest)
        self.batch = {"manifest": "manifest.json", "manifestSha256": hashlib.sha256(
            (self.root / "manifest.json").read_bytes()).hexdigest(), "cases": []}

    def write(self, name, value):
        if name.endswith(".json") and name not in {"manifest.json", "batch.json"}:
            value = dict(value)
            value.setdefault("modelCalls", 1)
            value.setdefault("modelIdentities", [self.identity] * (value["modelCalls"] or 0))
        (self.root / name).write_text(json.dumps(value))

    def row(self, source):
        self.write("result.json", source)
        self.batch["cases"].append({"id": "file", "attempt": 1, "evidence": "result.json"})

    def summary(self):
        return module.summarize(self.manifest, self.batch, self.root)

    def test_manifest_tracks_source_changes_without_per_case_override(self):
        template = json.loads((ROOT / "evals/real-tasks/manifest.json").read_text())
        for model in ("future-model", "other-model"):
            frozen = module.freeze_manifest(template,
                'const val PROVIDER_ID = "builtin"\nconst val MODEL_ID = "' + model + '"')
            self.assertEqual(model, frozen["modelIdentity"]["modelId"])
        template["cases"][-1]["arguments"]["helixModelId"] = "old-model"
        with self.assertRaises(ValueError):
            module.freeze_manifest(template, "")

    def test_missing_mixed_and_wrong_model_call_evidence_fail_closed(self):
        for identities in (None, [], [{"providerId": "builtin", "modelId": "old"}],
                           [self.identity, {"providerId": "other", "modelId": "candidate"}]):
            with self.subTest(identities=identities):
                self.batch["cases"] = []
                self.row({"completed": True, "modelCalls": 1, "modelIdentities": identities})
                with self.assertRaises(ValueError):
                    self.summary()

    def test_freeze_requires_unique_public_constants(self):
        template = json.loads((ROOT / "evals/real-tasks/manifest.json").read_text())
        for source in ('', 'const val PROVIDER_ID = "p"',
                       'const val PROVIDER_ID = "p"\nconst val MODEL_ID = "m"\nconst val MODEL_ID = "n"'):
            with self.assertRaises(ValueError):
                module.freeze_manifest(template, source)

    def test_manifest_argument_cannot_substitute_a_different_identity(self):
        self.manifest = dict(self.manifest, modelIdentity={"providerId": "p", "modelId": "other"})
        with self.assertRaises(ValueError):
            self.summary()

    def test_empty_or_unfrozen_identity_fails_closed(self):
        for identity in (None, {}, {"providerId": "p", "modelId": ""}):
            self.manifest["modelIdentity"] = identity
            with self.assertRaises(ValueError):
                self.summary()

    def test_source_uses_builtin_model_in_all_four_tasks(self):
        roots = (ROOT / "app/src/androidTest/kotlin", ROOT / "app/src/androidTestDeveloper/kotlin")
        template = json.loads((ROOT / "evals/real-tasks/manifest.json").read_text())
        for case in template["cases"]:
            relative = case["test"].split("#")[0].replace(".", "/") + ".kt"
            source = next(root / relative for root in roots if (root / relative).exists()).read_text()
            self.assertIn("BuiltInModel.PROVIDER_ID", source)
            self.assertIn("BuiltInModel.MODEL_ID", source)
            self.assertIn('"modelIdentities"', source)
            self.assertIn("it.providerSnapshot", source)

    def test_completed_turn_is_not_business_pass_and_missing_metrics_are_unknown(self):
        self.row({"state": "COMPLETED", "wireRequests": 3, "tools": [{}, {}]})
        report = self.summary()
        self.assertEqual(0, report["businessCompleted"])
        self.assertEqual(4, report["planned"])
        self.assertEqual(1, report["observed"])
        self.assertIsNone(report["cases"][0]["metrics"]["humanInterventions"])
        self.assertEqual(2, report["cases"][0]["metrics"]["toolCalls"])

    def test_auth_is_not_model_failure_even_with_false_success_claim(self):
        self.row({"completed": True, "httpStatuses": [401]})
        self.assertEqual("AUTH", self.summary()["cases"][0]["attribution"])
        self.assertEqual(0, self.summary()["businessCompleted"])
        self.assertIsNone(self.summary()["modelQualityRate"])

    def test_blocked_tasks_not_executed_failed_or_successful(self):
        self.write("auth.json", {"errorCode": "AUTH"})
        self.batch["cases"] = [{"id": "file", "attempt": 0, "status": "BLOCKED", "attribution": {
            "category": "AUTH", "reason": "Same configured account unavailable", "evidence": ["auth.json"]}}]
        report = self.summary()
        self.assertEqual(0, report["observed"])
        self.assertEqual(1, report["blocked"])
        self.assertEqual("BLOCKED", report["cases"][0]["status"])

    def test_verified_success_keeps_planned_denominator_and_fixture_approvals(self):
        self.row({"completed": True, "wireRequests": 2, "tools": [{}], "elapsedMs": 50,
                  "humanInterventions": 0, "fixtureApprovals": 1})
        report = self.summary()
        self.assertEqual(.25, report["completionRateOverPlanned"])
        self.assertEqual(1, report["cases"][0]["metrics"]["fixtureApprovals"])
        self.assertFalse(report["allRequiredMetricsMeasured"])

    def test_duplicate_attempts_fail_closed(self):
        self.row({"completed": False})
        self.batch["cases"] *= 2
        with self.assertRaises(ValueError):
            self.summary()

    def test_model_attribution_requires_evidence(self):
        self.row({"completed": False})
        self.batch["cases"][0]["attribution"] = {"category": "MODEL", "reason": "Stopped early"}
        with self.assertRaises(ValueError):
            self.summary()

    def test_manifest_change_and_escape_fail_closed(self):
        self.write("manifest.json", {})
        with self.assertRaises(ValueError):
            self.summary()
        with self.assertRaises(ValueError):
            module.artifact(self.root, "../other.json")

    def test_malformed_metric_is_not_silently_counted(self):
        self.row({"completed": True, "wireRequests": True})
        with self.assertRaises(ValueError):
            self.summary()

    def test_room_auth_code_is_service_attribution_without_wire_capture(self):
        self.row({"completed": False, "turns": [{"errorCode": "AUTH"}], "wireRequests": None})
        self.assertEqual("AUTH", self.summary()["cases"][0]["attribution"])

    def test_transport_count_is_optional_but_logical_calls_are_required(self):
        source = {"completed": True, "modelCalls": 2, "wireRequests": None, "tools": [{}],
                  "elapsedMs": 50, "humanInterventions": 0, "fixtureApprovals": 0}
        self.batch["cases"] = [{"id": c["id"], "attempt": 1, "evidence": c["id"] + ".json"}
                               for c in self.manifest["cases"]]
        for row in self.batch["cases"]:
            self.write(row["evidence"], source)
        self.assertTrue(self.summary()["allRequiredMetricsMeasured"])
        source["modelCalls"] = None
        source["modelIdentities"] = []
        self.write("file.json", source)
        with self.assertRaises(ValueError):
            self.summary()

    def test_cannot_reuse_one_success_for_different_tasks(self):
        self.row({"completed": True})
        self.batch["cases"].append({"id": "web", "attempt": 1, "evidence": "result.json"})
        with self.assertRaises(ValueError):
            self.summary()

    def test_cli_all_pass_accepts_unknown_optional_transport_count(self):
        source = {"completed": True, "modelCalls": 2, "wireRequests": None, "tools": [{}],
                  "elapsedMs": 50, "humanInterventions": 0, "fixtureApprovals": 0}
        self.batch["cases"] = [{"id": c["id"], "attempt": 1, "evidence": c["id"] + ".json"}
                               for c in self.manifest["cases"]]
        for row in self.batch["cases"]:
            self.write(row["evidence"], source)
        self.write("batch.json", self.batch)
        output = self.root / "report.json"
        process = subprocess.run(
            [sys.executable, str(ROOT / "scripts/device_baseline/real_tasks.py"),
             str(self.root / "batch.json"), "--output", str(output)],
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(0, process.returncode, process.stderr)
        report = json.loads(output.read_text())
        self.assertEqual(4, report["businessCompleted"])
        self.assertTrue(report["allRequiredMetricsMeasured"])
        self.assertTrue(all(row["metrics"]["wireRequests"] is None for row in report["cases"]))
