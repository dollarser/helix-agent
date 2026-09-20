#!/usr/bin/env python3
"""Unit tests for verify-terminal-runtime.py."""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPTS_DIR = Path(__file__).resolve().parent.parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

SPEC = importlib.util.spec_from_file_location("verify_terminal_runtime", SCRIPTS_DIR / "verify-terminal-runtime.py")
TERMINAL_MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TERMINAL_MODULE)
verify_terminal_runtime = TERMINAL_MODULE.verify_terminal_runtime

from acceptance_reports import EvidenceError


class TestTerminalReportsVerification(unittest.TestCase):
    def setUp(self):
        self.fixtures_dir = SCRIPTS_DIR / "fixtures" / "acceptance"
        self.valid_manifest_path = self.fixtures_dir / "valid_terminal_fixture_manifest.json"
        with self.valid_manifest_path.open("r", encoding="utf-8") as f:
            self.base_data = json.load(f)

    def write_manifest(self, data: dict, tmp_dir: Path) -> Path:
        p = tmp_dir / "manifest.json"
        with p.open("w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)
        return p

    def test_help_flag(self):
        script_path = SCRIPTS_DIR / "verify-terminal-runtime.py"
        res = subprocess.run([sys.executable, str(script_path), "--help"], capture_output=True, text=True)
        self.assertEqual(res.returncode, 0)
        self.assertIn("HXA-199", res.stdout)
        self.assertIn("--manifest", res.stdout)
        self.assertIn("--output", res.stdout)

    def test_valid_terminal_fixture(self):
        with tempfile.TemporaryDirectory() as tmp:
            out_dir = Path(tmp) / "report_out"
            code = verify_terminal_runtime(self.valid_manifest_path, out_dir)
            self.assertEqual(code, 0)
            report_json = out_dir / "report.json"
            report_md = out_dir / "report.md"
            self.assertTrue(report_json.is_file())
            self.assertTrue(report_md.is_file())
            with report_json.open("r", encoding="utf-8") as f:
                r = json.load(f)
            self.assertEqual(r["identity"]["scope"], "HXA-199")
            self.assertEqual(r["verdict"], "FIXTURE_INCOMPLETE")

    def test_missing_mandatory_terminal_scene(self):
        data = copy.deepcopy(self.base_data)
        # Remove dual_session_pty
        data["scenes"] = [s for s in data["scenes"] if s["scene_id"] != "dual_session_pty"]
        data["counts"]["expected"] -= 1
        data["counts"]["executed"] -= 1
        data["counts"]["skipped"] -= 1
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            m = self.write_manifest(data, tmp_path)
            out = tmp_path / "out"
            with self.assertRaises(EvidenceError) as ctx:
                verify_terminal_runtime(m, out)
            self.assertEqual(ctx.exception.exit_code, 2)
            self.assertIn("Missing mandatory HXA-199 scenes", str(ctx.exception))

    def test_fake_2h_lease_rejected(self):
        data = copy.deepcopy(self.base_data)
        # Attempt to mark full_lease_2h as passed with only 300s
        for s in data["scenes"]:
            if s["scene_id"] == "full_lease_2h":
                s["status"] = "passed"
                s.pop("skip_reason", None)
                s["metrics"] = {"duration_seconds": 300}
        data["counts"]["skipped"] -= 1
        data["counts"]["passed"] += 1
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            m = self.write_manifest(data, tmp_path)
            out = tmp_path / "out"
            with self.assertRaises(EvidenceError) as ctx:
                verify_terminal_runtime(m, out)
            self.assertEqual(ctx.exception.exit_code, 2)
            self.assertIn("full_lease_2h requires duration_seconds >= 7200", str(ctx.exception))

    def test_fake_30m_idle_rejected(self):
        data = copy.deepcopy(self.base_data)
        for s in data["scenes"]:
            if s["scene_id"] == "detach_idle_30m":
                s["status"] = "passed"
                s.pop("skip_reason", None)
                s["metrics"] = {"duration_seconds": 60}
        data["counts"]["skipped"] -= 1
        data["counts"]["passed"] += 1
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            m = self.write_manifest(data, tmp_path)
            out = tmp_path / "out"
            with self.assertRaises(EvidenceError) as ctx:
                verify_terminal_runtime(m, out)
            self.assertEqual(ctx.exception.exit_code, 2)
            self.assertIn("detach_idle_30m requires duration_seconds >= 1800", str(ctx.exception))

    def test_fake_16k_pagesize_rejected(self):
        data = copy.deepcopy(self.base_data)
        for s in data["scenes"]:
            if s["scene_id"] == "real_16k_pagesize":
                s["status"] = "passed"
                s.pop("skip_reason", None)
                s["metrics"] = {"page_size_bytes": 4096}
        data["counts"]["skipped"] -= 1
        data["counts"]["passed"] += 1
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            m = self.write_manifest(data, tmp_path)
            out = tmp_path / "out"
            with self.assertRaises(EvidenceError) as ctx:
                verify_terminal_runtime(m, out)
            self.assertEqual(ctx.exception.exit_code, 2)
            self.assertIn("real_16k_pagesize requires page_size_bytes == 16384", str(ctx.exception))

    def test_failed_scene_returns_1(self):
        data = copy.deepcopy(self.base_data)
        data["scenes"][0]["status"] = "failed"
        data["counts"]["passed"] -= 1
        data["counts"]["failed"] += 1
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            m = self.write_manifest(data, tmp_path)
            out = tmp_path / "out"
            code = verify_terminal_runtime(m, out)
            self.assertEqual(code, 1)

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
                verify_terminal_runtime(m, out)
            self.assertEqual(ctx.exception.exit_code, 2)
            self.assertIn("closed=True", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
