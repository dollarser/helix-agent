import fcntl
import importlib.util
import subprocess
import sys
import tempfile
import unittest
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
