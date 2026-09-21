#!/usr/bin/env python3
"""Unit tests for verify-product-journeys.py and acceptance_reports.py."""
import copy
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPTS_DIR = Path(__file__).resolve().parent.parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

SPEC = importlib.util.spec_from_file_location("verify_product_journeys", SCRIPTS_DIR / "verify-product-journeys.py")
VERIFY_MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY_MODULE)
verify_product_journeys = VERIFY_MODULE.verify_product_journeys

import acceptance_reports
from acceptance_reports import EvidenceError, safe_load_json


class TestProductJourneysVerification(unittest.TestCase):
    def setUp(self):
        self.fixtures_dir = SCRIPTS_DIR / "fixtures" / "acceptance"
        self.valid_manifest_path = self.fixtures_dir / "valid_fixture_manifest.json"
        with self.valid_manifest_path.open("r", encoding="utf-8") as f:
            self.base_data = json.load(f)

    def write_manifest(self, data: dict, tmp_dir: Path) -> Path:
        p = tmp_dir / "manifest.json"
        with p.open("w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)
        ev_src = self.fixtures_dir / "evidence"
        if ev_src.exists():
            ev_dst = tmp_dir / "evidence"
            if not ev_dst.exists():
                shutil.copytree(ev_src, ev_dst)
        return p

    def test_help_flag(self):
        script_path = SCRIPTS_DIR / "verify-product-journeys.py"
        res = subprocess.run([sys.executable, str(script_path), "--help"], capture_output=True, text=True)
        self.assertEqual(res.returncode, 0)
        self.assertIn("HXA-206", res.stdout)
        self.assertIn("--manifest", res.stdout)
        self.assertIn("--output", res.stdout)

    def test_valid_fixture_manifest(self):
        with tempfile.TemporaryDirectory() as tmp:
            out_dir = Path(tmp) / "report_out"
            code = verify_product_journeys(self.valid_manifest_path, out_dir)
            self.assertEqual(code, 0)
            report_json = out_dir / "report.json"
            report_md = out_dir / "report.md"
            self.assertTrue(report_json.is_file())
            self.assertTrue(report_md.is_file())
            with report_json.open("r", encoding="utf-8") as f:
                r = json.load(f)
            self.assertEqual(r["verdict"], "FIXTURE_ONLY")
            self.assertEqual(r["identity"]["scope"], "HXA-206")
            self.assertEqual(r["counts"]["passed"], 6)

    def test_valid_real_mode(self):
        data = copy.deepcopy(self.base_data)
        data["mode"] = "real"
        data["app_apk_sha256"] = "a" * 64
        data["test_apk_sha256"] = "b" * 64
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            m = self.write_manifest(data, tmp_path)
            out = tmp_path / "out"
            code = verify_product_journeys(m, out)
            self.assertEqual(code, 0)
            with (out / "report.json").open("r") as f:
                r = json.load(f)
            self.assertEqual(r["verdict"], "PASS")

    def test_failed_scene_returns_exit_code_1(self):
        data = copy.deepcopy(self.base_data)
        data["scenes"][0]["status"] = "failed"
        data["counts"]["passed"] -= 1
        data["counts"]["failed"] += 1
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            m = self.write_manifest(data, tmp_path)
            out = tmp_path / "out"
            code = verify_product_journeys(m, out)
            self.assertEqual(code, 1)
            with (out / "report.json").open("r") as f:
                r = json.load(f)
            self.assertEqual(r["verdict"], "FAIL")

    def test_missing_mandatory_scene(self):
        data = copy.deepcopy(self.base_data)
        # Remove git_r1_readonly
        data["scenes"] = [s for s in data["scenes"] if s["scene_id"] != "git_r1_readonly"]
        data["counts"]["expected"] -= 1
        data["counts"]["executed"] -= 1
        data["counts"]["passed"] -= 1
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            m = self.write_manifest(data, tmp_path)
            out = tmp_path / "out"
            with self.assertRaises(EvidenceError) as ctx:
                verify_product_journeys(m, out)
            self.assertEqual(ctx.exception.exit_code, 2)
            self.assertIn("Missing mandatory HXA-206 scenes", str(ctx.exception))

    def test_unclosed_owner_in_real_mode(self):
        data = copy.deepcopy(self.base_data)
        data["mode"] = "real"
        data["app_apk_sha256"] = "a" * 64
        data["test_apk_sha256"] = "b" * 64
        data["device_lifecycle"]["closed"] = False
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            m = self.write_manifest(data, tmp_path)
            out = tmp_path / "out"
            with self.assertRaises(EvidenceError) as ctx:
                verify_product_journeys(m, out)
            self.assertEqual(ctx.exception.exit_code, 2)
            self.assertIn("closed=True", str(ctx.exception))

    def test_zero_executed(self):
        data = copy.deepcopy(self.base_data)
        data["counts"]["executed"] = 0
        data["scenes"] = []
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            m = self.write_manifest(data, tmp_path)
            out = tmp_path / "out"
            with self.assertRaises(EvidenceError) as ctx:
                verify_product_journeys(m, out)
            self.assertEqual(ctx.exception.exit_code, 2)
            self.assertIn("Zero test executions detected", str(ctx.exception))

    def test_count_mismatch(self):
        data = copy.deepcopy(self.base_data)
        data["counts"]["passed"] = 100  # Doesn't match executed
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            m = self.write_manifest(data, tmp_path)
            out = tmp_path / "out"
            with self.assertRaises(EvidenceError) as ctx:
                verify_product_journeys(m, out)
            self.assertEqual(ctx.exception.exit_code, 2)
            self.assertIn("Count mismatch", str(ctx.exception))

    def test_duplicate_scene_id(self):
        data = copy.deepcopy(self.base_data)
        data["scenes"].append(copy.deepcopy(data["scenes"][0]))
        data["counts"]["expected"] += 1
        data["counts"]["executed"] += 1
        data["counts"]["passed"] += 1
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            m = self.write_manifest(data, tmp_path)
            out = tmp_path / "out"
            with self.assertRaises(EvidenceError) as ctx:
                verify_product_journeys(m, out)
            self.assertEqual(ctx.exception.exit_code, 2)
            self.assertIn("Duplicate scene_id", str(ctx.exception))

    def test_duplicate_json_keys(self):
        raw = '{"schema_version": 1, "scope": "HXA-206", "scope": "HXA-206"}'
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            m = tmp_path / "bad.json"
            m.write_text(raw, encoding="utf-8")
            out = tmp_path / "out"
            with self.assertRaises(EvidenceError) as ctx:
                verify_product_journeys(m, out)
            self.assertEqual(ctx.exception.exit_code, 2)
            self.assertIn("Duplicate JSON key", str(ctx.exception))

    def test_skipped_without_reason(self):
        data = copy.deepcopy(self.base_data)
        data["scenes"][0]["status"] = "skipped"
        data["scenes"][0]["skip_reason"] = ""
        data["counts"]["passed"] -= 1
        data["counts"]["skipped"] += 1
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            m = self.write_manifest(data, tmp_path)
            out = tmp_path / "out"
            with self.assertRaises(EvidenceError) as ctx:
                verify_product_journeys(m, out)
            self.assertEqual(ctx.exception.exit_code, 2)
            self.assertIn("requires non-empty skip_reason", str(ctx.exception))

    def test_path_escape_detection(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp) / "base"
            base.mkdir()
            outside = Path(tmp) / "outside.json"
            outside.write_text('{"a": 1}', encoding="utf-8")
            with self.assertRaises(EvidenceError) as ctx:
                safe_load_json(Path("../outside.json"), base_dir=base)
            self.assertEqual(ctx.exception.exit_code, 2)
            self.assertIn("escapes", str(ctx.exception))

    def test_real_pass_despite_failed_raw_log_rejected(self):
        data = copy.deepcopy(self.base_data)
        data.update(
            mode="real",
            commit_sha="a" * 40,
            app_apk_sha256="b" * 64,
            test_apk_sha256="c" * 64,
            device_lifecycle={"owner_pid": 12345, "closed": True},
        )
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            (tmp_path / "actual-failure.log").write_text("FAILURES!!!\nTests run: 1, Failures: 1\nINSTRUMENTATION_CODE: 0\n")
            for scene in data["scenes"]:
                scene["source_ref"] = "actual-failure.log"
                scene["status"] = "passed"
            m = self.write_manifest(data, tmp_path)
            out = tmp_path / "out"
            with self.assertRaises(EvidenceError) as ctx:
                verify_product_journeys(m, out)
            self.assertEqual(ctx.exception.exit_code, 2)
            self.assertIn("contains failure", str(ctx.exception))

    def test_missing_expected_executions_rejected(self):
        data = copy.deepcopy(self.base_data)
        data["counts"]["expected"] += 10
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            m = self.write_manifest(data, tmp_path)
            out = tmp_path / "out"
            with self.assertRaises(EvidenceError) as ctx:
                verify_product_journeys(m, out)
            self.assertEqual(ctx.exception.exit_code, 2)
            self.assertIn("Count mismatch", str(ctx.exception))

    def test_all_mandatory_skipped_returns_nonzero(self):
        data = copy.deepcopy(self.base_data)
        data.update(
            mode="real",
            commit_sha="a" * 40,
            app_apk_sha256="b" * 64,
            test_apk_sha256="c" * 64,
            device_lifecycle={"owner_pid": 12345, "closed": True},
        )
        for s in data["scenes"]:
            s.update(status="skipped", skip_reason="not run")
        data["counts"].update(passed=0, skipped=len(data["scenes"]))
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            m = self.write_manifest(data, tmp_path)
            out = tmp_path / "out"
            code = verify_product_journeys(m, out)
            self.assertEqual(code, 1)
            with (out / "report.json").open("r", encoding="utf-8") as f:
                rep = json.load(f)
            self.assertEqual(rep["verdict"], "INCOMPLETE")


if __name__ == "__main__":
    unittest.main()
