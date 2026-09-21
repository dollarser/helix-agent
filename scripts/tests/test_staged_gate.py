"""Index/worktree disagreement, binary and scanner-failure regression tests."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
CHECKER = ROOT / "scripts/check-staged.py"
TOKEN = "sk-" + "X" * 30  # Synthetic; build at runtime so source never contains a credential-shaped value.


class StagedGateTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.repo = Path(self.directory.name)
        self.git("init", "--quiet")

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.repo, stderr=subprocess.DEVNULL)

    def stage(self, name, content):
        (self.repo / name).write_bytes(content)
        self.git("add", "--", name)

    def run_gate(self, env=None):
        return subprocess.run([sys.executable, str(CHECKER)], cwd=self.repo, env=env,
                              capture_output=True, text=True, timeout=10)

    def assert_blocked_without_secret(self, result):
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn(TOKEN, result.stdout + result.stderr)

    def test_unborn_and_empty_index(self):
        self.assertEqual(0, self.run_gate().returncode)
        self.stage("hello.txt", b"safe\n")
        self.assertEqual(0, self.run_gate().returncode)

    def test_unstaged_cleanup_cannot_hide_staged_secret(self):
        self.stage("config.txt", TOKEN.encode() + b"\n")
        (self.repo / "config.txt").write_text("clean worktree\n")
        self.assert_blocked_without_secret(self.run_gate())

    def test_unstaged_secret_does_not_change_clean_index_result(self):
        self.stage("config.txt", b"clean index\n")
        (self.repo / "config.txt").write_text(TOKEN)
        self.assertEqual(0, self.run_gate().returncode)

    def test_binary_and_unusual_filename(self):
        self.stage("binary name\nwith newline.bin", b"\x00data\x00" + TOKEN.encode() + b"\x00")
        self.assert_blocked_without_secret(self.run_gate())

    def test_large_blob_is_streamed_and_fully_checked(self):
        self.stage("large.bin", b"\x00" * (4 * 1024 * 1024) + TOKEN.encode())
        self.assert_blocked_without_secret(self.run_gate())

    def test_rename_and_deletion(self):
        self.stage("old.txt", b"safe\n")
        self.git("-c", "core.hooksPath=/dev/null", "-c", "commit.gpgsign=false",
                 "-c", "user.name=Fixture", "-c", "user.email=fixture@example.test", "commit", "--quiet", "-m", "seed")
        self.git("mv", "old.txt", "new name.txt")
        self.assertEqual(0, self.run_gate().returncode)
        self.git("rm", "--force", "--", "new name.txt")
        self.assertEqual(0, self.run_gate().returncode)

    def test_symlink_content_is_scanned_without_following_target(self):
        (self.repo / "outside.txt").write_text(TOKEN)
        (self.repo / "link").symlink_to("outside.txt")
        self.git("add", "--", "link")
        self.assertEqual(0, self.run_gate().returncode)
        (self.repo / "link").unlink()
        (self.repo / "link").symlink_to(TOKEN)
        self.git("add", "--", "link")
        self.assert_blocked_without_secret(self.run_gate())

    def test_scanner_error_fails_closed(self):
        self.stage("safe.txt", b"safe\n")
        binary = self.repo / "fake-bin"
        binary.mkdir()
        scanner = binary / "rg"
        scanner.write_text("#!/bin/sh\nexit 2\n")
        scanner.chmod(0o755)
        result = self.run_gate(dict(os.environ, PATH=str(binary) + os.pathsep + os.environ["PATH"]))
        self.assert_blocked_without_secret(result)
        self.assertIn("scanner failed", result.stderr)

    def test_whitespace_is_checked_from_index(self):
        self.stage("spaces.txt", b"trailing  \n")
        (self.repo / "spaces.txt").write_text("clean\n")
        self.assertNotEqual(0, self.run_gate().returncode)

    def test_shared_pattern_matches_full_scanner_without_echoing(self):
        scripts = self.repo / "scripts"
        scripts.mkdir()
        for name in ("check-secrets.sh", "secret-pattern.txt"):
            (scripts / name).write_bytes((ROOT / "scripts" / name).read_bytes())
        (self.repo / "secret.txt").write_text(TOKEN)
        result = subprocess.run(["bash", str(scripts / "check-secrets.sh")], cwd=self.repo,
                                capture_output=True, text=True, timeout=10)
        self.assert_blocked_without_secret(result)
        (self.repo / "secret.txt").unlink()
        result = subprocess.run(["bash", str(scripts / "check-secrets.sh")], cwd=self.repo,
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
