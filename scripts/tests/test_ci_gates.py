"""Exercise gate dispatch and exit propagation without running Gradle."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class CiGatesTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / 'scripts').mkdir()
        for name in ('check-all.sh', 'ci-run-gate.sh'):
            shutil.copy2(ROOT / 'scripts' / name, self.root / 'scripts' / name)
        gradle = self.root / 'gradlew'
        gradle.write_text('#!/usr/bin/env bash\nprintf "%s\\n" "$@" > gradle-args.txt\nexit "${FAKE_EXIT:-0}"\n')
        gradle.chmod(0o755)

    def run_gate(self, gate, wrapper=False, exit_code=0):
        script = 'ci-run-gate.sh' if wrapper else 'check-all.sh'
        env = dict(os.environ, FAKE_EXIT=str(exit_code), HELIX_GRADLE_PROFILE='0')
        result = subprocess.run(['bash', str(self.root / 'scripts' / script), gate],
                                cwd=self.root, env=env, capture_output=True, text=True)
        args = (self.root / 'gradle-args.txt').read_text().splitlines()
        return result, args

    def test_split_preserves_every_full_analysis_task(self):
        full, full_args = self.run_gate('--analysis')
        debug, debug_args = self.run_gate('--debug-analysis')
        release, release_args = self.run_gate('--release-analysis')
        self.assertEqual([0, 0, 0], [full.returncode, debug.returncode, release.returncode])
        self.assertEqual(set(full_args), set(debug_args) | set(release_args))
        self.assertFalse(set(debug_args) & set(release_args))
        self.assertIn('lintDeveloperRelease', release_args)
        self.assertIn('detekt', debug_args)
        self.assertNotIn('--profile', full_args)

    def test_ci_profile_and_failure_are_preserved(self):
        for gate in ('--debug-analysis', '--release-analysis'):
            result, args = self.run_gate(gate, wrapper=True, exit_code=7)
            self.assertEqual(7, result.returncode)
            self.assertIn('--profile', args)
            report = self.root / 'build/ci' / (gate[2:] + '.tsv')
            self.assertEqual('7', report.read_text().strip().split('\t')[-1])


if __name__ == '__main__':
    unittest.main()
