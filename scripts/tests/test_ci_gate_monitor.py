"""Exercise real child execution, timeout and signal preservation without Gradle."""
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]


class GateMonitorTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        (self.root / 'scripts').mkdir()
        self.command = [sys.executable, str(ROOT / 'scripts/ci/run_gate.py'), 'analysis', '--timeout', '3']
        self.env = dict(os.environ, GITHUB_ACTIONS='false')
        self.env.pop('HELIX_CI_JOB_DEADLINE', None)

    def script(self, body):
        path = self.root / 'scripts/check-all.sh'
        path.write_text('#!/usr/bin/env bash\n' + body)
        path.chmod(0o755)

    def record(self):
        return json.loads((self.root / 'build/ci/analysis.json').read_text())

    def test_success_and_failure_keep_output_and_exit(self):
        for code in (0, 7):
            self.script(f'echo "> Task :app:lint"\nexit {code}\n')
            result = subprocess.run(self.command, cwd=self.root, env=self.env, capture_output=True)
            self.assertEqual(code, result.returncode)
            self.assertIn(b'> Task :app:lint', result.stdout)
            self.assertEqual('> Task :app:lint', self.record()['lastEmittedTask'])
            self.assertEqual('exit', self.record()['reason'])

    def test_deadline_fails_with_diagnostics_and_kills_child(self):
        self.script('echo "> Task :app:blocked"\nsleep 10\ntouch escaped\n')
        started = time.monotonic()
        result = subprocess.run(self.command + ['--timeout', '0.2'], cwd=self.root,
                                env=self.env, capture_output=True, timeout=12)
        self.assertEqual(124, result.returncode)
        self.assertLess(time.monotonic() - started, 10)
        self.assertEqual('deadline', self.record()['reason'])
        self.assertTrue((self.root / 'build/ci/analysis-diagnostics/processes.txt').is_file())
        self.assertFalse((self.root / 'escaped').exists())

    def test_expired_job_budget_never_starts_gate(self):
        self.script('touch executed\n')
        env = dict(self.env, HELIX_CI_JOB_DEADLINE=str(time.time() - 1))
        result = subprocess.run(self.command, cwd=self.root, env=env, capture_output=True)
        self.assertEqual(124, result.returncode)
        self.assertFalse((self.root / 'executed').exists())

    def test_cancellation_is_not_success(self):
        self.script('touch started\nsleep 10\n')
        process = subprocess.Popen(self.command, cwd=self.root, env=self.env,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            deadline = time.monotonic() + 3
            while not (self.root / 'started').exists() and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertTrue((self.root / 'started').exists())
            process.send_signal(signal.SIGTERM)
            process.communicate(timeout=12)
            self.assertEqual(143, process.returncode)
            self.assertEqual('signal', self.record()['reason'])
        finally:
            if process.poll() is None:
                process.kill()
                process.communicate()

    def test_workflow_keeps_checks_and_limits_cache_writers(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        self.assertEqual(2, workflow.count("branches: [main, dev]"))
        self.assertIn("'!config/asr/README.md'", workflow)
        self.assertEqual(2, workflow.count('cache-read-only:'))
        self.assertIn("github.ref != 'refs/heads/dev'", workflow)
        for gate in ('--analysis', '--tests-build', '--artifacts', '--release-artifacts'):
            self.assertIn('ci-run-gate.sh ' + gate, workflow)
        self.assertIn('timeout-minutes: 60', workflow)
        self.assertNotIn('connectedAndroidTest', workflow)

    def test_profile_input_uses_job_context_and_cache_saves_keep_success_guard(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        self.assertIn("if-no-files-found: ${{ job.status == 'success' && 'error' || 'ignore' }}", workflow)
        self.assertNotIn("if-no-files-found: ${{ success()", workflow)
        for name in ('Save verified downloads', 'Save verified speech inputs'):
            step = workflow.split('- name: ' + name, 1)[1].split('- name:', 1)[0]
            condition = next(line.strip() for line in step.splitlines() if line.strip().startswith('if:'))
            self.assertIn("github.ref == 'refs/heads/main'", condition)
            self.assertIn("github.ref == 'refs/heads/dev'", condition)
            self.assertIn("github.event_name != 'pull_request'", condition)
            # No status function overrides Actions' implicit success() guard.
            for status in ('always(', 'failure(', 'cancelled('):
                self.assertNotIn(status, condition)
