import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from acceptance_reports import EvidenceError, TestCounts, verify_source_evidence
from owned_acceptance import collect_owned, sha256, split_recovery_log, test_records


class OwnedEvidenceTest(unittest.TestCase):
    def test_recovery_split_preserves_raw_phases_and_rejects_current_duplicates(self):
        old = "09-21 10:00:00.001  123  124 I TestRunner: started: read(A)\n"
        start = "09-21 10:00:01.001  456  457 I TestRunner: started: read(A)\n"
        end = "09-21 10:00:02.001  456  457 I TestRunner: finished: read(A)\n"
        current, prior = split_recovery_log(old + start + end, 123)
        self.assertEqual(prior, old)
        self.assertEqual(current, start + end)
        self.assertEqual(test_records(current)["A#read"]["status"], "passed")
        current, _ = split_recovery_log(old + start + start + end, 123)
        with self.assertRaises(EvidenceError):
            test_records(current)

    def test_boolean_is_not_a_test_count(self):
        with self.assertRaises(EvidenceError):
            TestCounts(1, True, 1, 0, 0).validate()

    def test_exact_completion_and_assumption(self):
        records = test_records("\n".join(f"I TestRunner: {event}: read(A)" for event in
                                        ("started", "assumption failed", "finished")))
        self.assertEqual(records["A#read"]["status"], "skipped")

    def test_missing_start_duplicate_and_class_only_rejected(self):
        for text in ("A: OK (1 test)", "I TestRunner: finished: read(A)",
                     "I TestRunner: started: read(A)\nI TestRunner: started: read(A)"):
            with self.subTest(text=text), self.assertRaises(EvidenceError):
                test_records(text)

    def test_metrics_must_be_emitted_once_inside_the_same_method(self):
        metric = 'I HelixAcceptance: {"test_class":"A","test_method":"read","metrics":{"first_packet_ms":37}}'
        start = "I TestRunner: started: read(A)"
        finish = "I TestRunner: finished: read(A)"
        self.assertEqual(test_records("\n".join((start, metric, finish)))["A#read"]["metrics"], {"first_packet_ms": 37})
        for lines in ((metric, start, finish), (start, finish, metric), (start, metric, metric, finish)):
            with self.subTest(lines=lines), self.assertRaises(EvidenceError):
                test_records("\n".join(lines))

    def test_real_source_requires_exact_success_and_measured_metrics(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            raw = root / "test.txt"
            for text in ("A", "read(A)", "I TestRunner: started: read(A)",
                         "I TestRunner: started: read(A)\nI TestRunner: assumption failed: read(A)\nI TestRunner: finished: read(A)"):
                raw.write_text(text)
                with self.assertRaises(EvidenceError):
                    verify_source_evidence("test.txt", root, "real", "passed", "A", "read")
            raw.write_text("I TestRunner: started: read(A)\nI TestRunner: finished: read(A)")
            verify_source_evidence("test.txt", root, "real", "passed", "A", "read")
            with self.assertRaises(EvidenceError):
                verify_source_evidence("test.txt", root, "real", "passed", "A", "read", {"duration_seconds": 7200})

    def test_owned_artifact_and_method_integrity(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name, value in (("owner", {"pid": 123, "serial": "emulator-5554"}),
                                ("closed", {"pid": 123, "exit": 0})):
                (root / f"{name}.json").write_text(json.dumps(value))
            for name in ("app", "test"):
                (root / f"{name}.apk").write_bytes(b"synthetic unit-test bytes")
            (root / "artifacts.json").write_text(json.dumps({name: sha256(root / f"{name}.apk") for name in ("app", "test")}))
            (root / "test-logcat.txt").write_text("I TestRunner: started: read(A)\nI TestRunner: finished: read(A)")
            (root / "instrumentation.txt").write_text("OK (1 test)\n")
            self.assertEqual(collect_owned(root, ["A#read"])["verdict"], "DEVICE_BATCH_PASS")
            with self.assertRaises(EvidenceError):
                collect_owned(root, ["A#unexecuted"])
            (root / "instrumentation.txt").write_text("FAILURES!!!\n")
            with self.assertRaises(EvidenceError):
                collect_owned(root, ["A#read"])
            (root / "instrumentation.txt").write_text("OK (1 test)\n")
            (root / "app.apk").write_bytes(b"tampered")
            with self.assertRaises(EvidenceError):
                collect_owned(root, ["A#read"])


if __name__ == "__main__":
    unittest.main()
