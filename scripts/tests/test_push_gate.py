import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('push_gate', ROOT / 'scripts/check-push.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class PushGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git('init', '--quiet')
        self.git('config', 'user.name', 'Fixture')
        self.git('config', 'user.email', 'fixture@example.invalid')
        self.git('config', 'core.hooksPath', '/dev/null')
        self.git('config', 'commit.gpgsign', 'false')
        (self.root / 'scripts/ci').mkdir(parents=True)
        (self.root / 'scripts/ci/plan.py').write_text((ROOT / 'scripts/ci/plan.py').read_text())
        (self.root / 'scripts/check-all.sh').write_text('#!/bin/sh\nset -eu\ntest "$1" = --source\ntest "$(cat payload)" = good\n')
        (self.root / 'payload').write_text('good\n')
        self.base = self.commit()
        (self.root / 'new.txt').write_text('clean\n')
        self.head = self.commit()
        self.cache = self.root / '.git/helix-hooks'
        self.cache.mkdir()

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.root), *args], text=True).strip()

    def commit(self):
        self.git('add', '.')
        self.git('commit', '--quiet', '-m', 'Fixture')
        return self.git('rev-parse', 'HEAD')

    def check(self, head=None):
        gate.check_snapshot(self.root, head or self.head, self.base, self.cache, 'tools')

    def test_dirty_index_and_worktree_do_not_change_pushed_snapshot(self):
        (self.root / 'payload').write_text('dirty staged\n')
        self.git('add', 'payload')
        (self.root / 'payload').write_text('dirty unstaged\n')
        before = self.git('diff', '--cached')
        self.check()
        self.assertEqual(before, self.git('diff', '--cached'))
        self.assertEqual('dirty unstaged\n', (self.root / 'payload').read_text())
        self.check()  # Same exact commit and tools: successful source receipt is reusable.

    def test_committed_failure_is_not_hidden_by_clean_worktree_or_cached_other_commit(self):
        self.check()
        (self.root / 'payload').write_text('bad\n')
        bad = self.commit()
        (self.root / 'payload').write_text('good\n')
        with self.assertRaisesRegex(RuntimeError, 'source gate failed'):
            self.check(bad)
        self.assertFalse((self.cache / f'{bad}-tools.json').exists())

    def test_unstaged_attributes_cannot_hide_committed_range_error(self):
        (self.root / 'bad.txt').write_text('trailing  \n')
        bad = self.commit()
        (self.root / '.gitattributes').write_text('* -whitespace\n')
        with self.assertRaisesRegex(RuntimeError, 'whitespace failed'):
            self.check(bad)

    def test_cached_source_never_skips_a_changed_whitespace_baseline(self):
        (self.root / 'bad.txt').write_text('trailing  \n')
        bad = self.commit()
        gate.check_snapshot(self.root, bad, bad, self.cache, 'tools')
        with self.assertRaisesRegex(RuntimeError, 'whitespace failed'):
            self.check(bad)

    def test_cancellation_does_not_write_a_pass_or_leave_a_snapshot(self):
        original = gate.subprocess.run
        def cancel(command, **kwargs):
            if command[0] == 'bash':
                raise KeyboardInterrupt()
            return original(command, **kwargs)
        with patch.object(gate.subprocess, 'run', side_effect=cancel), self.assertRaises(KeyboardInterrupt):
            self.check()
        self.assertEqual([], list(self.cache.glob('push-*')))
        self.assertFalse((self.cache / f'{self.head}-tools.json').exists())

    def test_all_refs_deletion_tags_and_exact_oid_are_processed(self):
        self.git('tag', '-a', 'fixture', '-m', 'Fixture', self.base)
        tag = self.git('rev-parse', 'fixture')
        raw = f'refs/heads/a {self.head} refs/heads/a {gate.ZERO}\nrefs/tags/fixture {tag} refs/tags/fixture {gate.ZERO}\n(delete) {gate.ZERO} refs/heads/gone {self.head}\n'
        with patch.object(gate, 'fingerprint', return_value='tools'), patch.object(gate, 'check_snapshot') as check:
            gate.run(self.root, raw, None, self.base)
        self.assertEqual([self.head, self.base], [call.args[1] for call in check.call_args_list])
        for raw in ['', f'(delete) {gate.ZERO} refs/heads/gone {self.head}\n']:
            with patch.object(gate, 'fingerprint', side_effect=AssertionError('Must not claim a source check')):
                gate.run(self.root, raw, None)
        result = subprocess.run([os.sys.executable, str(ROOT / 'scripts/check-push.py'), 'origin', 'file:///unused'],
                                cwd=self.root, input='', capture_output=True, text=True)
        self.assertEqual(0, result.returncode)

    def test_invalid_refs_baselines_and_api_failures_block(self):
        for url in ['git@github.com:owner/repo.git', 'https://github.com/owner/repo.git', 'ssh://git@github.com/owner/repo']:
            self.assertEqual('owner/repo', gate.repository(url))
        with self.assertRaises(ValueError):
            gate.repository('https://other.invalid/owner/repo')
        for raw in ['invalid', 'a not-an-oid b '+gate.ZERO]:
            with self.assertRaises(ValueError):
                gate.updates(raw)
        for base in ['f'*40, 'not a commit']:
            with self.assertRaises(ValueError):
                gate.baseline(self.root, self.head, None, base)
        with self.assertRaises(ValueError):
            gate.baseline(self.root, self.head, lambda _: {'workflow_runs': []})
        with self.assertRaises(OSError):
            gate.baseline(self.root, self.head, lambda _: (_ for _ in ()).throw(OSError('offline')))
        with patch.object(gate.subprocess, 'check_output', side_effect=FileNotFoundError('rg')):
            with self.assertRaises(FileNotFoundError):
                gate.fingerprint()
        from types import SimpleNamespace
        with patch.object(gate.subprocess, 'check_output', return_value='tool\n'), \
                patch.object(gate.subprocess, 'run', return_value=SimpleNamespace(returncode=0)):
            with self.assertRaisesRegex(RuntimeError, 'probe failed'):
                gate.fingerprint()

    def test_verified_baseline_uses_complete_android_run_not_remote_tip(self):
        def get(path):
            if 'jobs' in path:
                return {'jobs': [{'name': n, 'conclusion': 'success'} for n in ['source', 'runtime-assets', 'android', 'verify']]}
            return {'workflow_runs': [{'id': 1, 'event': 'push', 'head_sha': self.base}]}
        self.assertEqual(self.base, gate.baseline(self.root, self.head, get))

    def test_inherited_git_environment_does_not_retarget_snapshot(self):
        with patch.dict(os.environ, {'GIT_DIR': str(self.root / '.git'), 'GIT_INDEX_FILE': str(self.root / '.git/index')}):
            self.check()


class HookInstallerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        subprocess.run(['git', 'init', '--quiet', str(self.root)], check=True)
        (self.root / '.githooks').mkdir()
        for name in ['pre-commit', 'pre-push']:
            path = self.root / '.githooks' / name
            path.write_text('#!/bin/sh\nexit 0\n')
            path.chmod(0o755)

    def setup_hook(self, *args):
        return subprocess.run([os.sys.executable, str(ROOT / 'scripts/install-hooks.py'), *args], cwd=self.root,
                              capture_output=True, text=True)

    def test_status_does_not_install_but_explicit_install_is_local(self):
        self.assertEqual(0, self.setup_hook().returncode)
        command = ['git', '-C', str(self.root), 'config', '--local', '--get', 'core.hooksPath']
        self.assertNotEqual(0, subprocess.run(command, capture_output=True).returncode)
        self.assertEqual(0, self.setup_hook('--install').returncode)
        self.assertEqual('.githooks', subprocess.check_output(command, text=True).strip())
        self.assertEqual(0, self.setup_hook('--install').returncode)

    def test_existing_hooks_and_configuration_are_never_replaced(self):
        hook = self.root / '.git/hooks/pre-push'
        hook.write_text('#!/bin/sh\nexit 7\n');hook.chmod(0o755)
        self.assertNotEqual(0, self.setup_hook('--install').returncode)
        self.assertIn('exit 7', hook.read_text())
        hook.unlink()
        subprocess.run(['git', '-C', str(self.root), 'config', 'core.hooksPath', '/other/hooks'], check=True)
        self.assertNotEqual(0, self.setup_hook('--install').returncode)
        subprocess.run(['git', '-C', str(self.root), 'config', 'core.hooksPath', ''], check=True)
        self.assertNotEqual(0, self.setup_hook('--install').returncode)

    def test_shared_worktree_config_is_not_installed_implicitly(self):
        command = ['git', '-C', str(self.root)]
        subprocess.run(command + ['-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                                  '-c', 'commit.gpgsign=false', 'commit', '--allow-empty', '-qm', 'Fixture'], check=True)
        subprocess.run(command + ['-c', 'core.hooksPath=/dev/null', 'worktree', 'add', '--quiet', '--detach',
                                  str(self.root / 'linked')], check=True)
        self.assertNotEqual(0, self.setup_hook('--install').returncode)
        self.assertNotEqual(0, subprocess.run(command + ['config', '--local', '--get', 'core.hooksPath'],
                                            capture_output=True).returncode)
        self.assertNotEqual(0, self.setup_hook('--install', '--worktree').returncode)
        subprocess.run(command + ['config', 'extensions.worktreeConfig', 'true'], check=True)
        shared = (self.root / '.git/config').read_bytes()
        self.assertEqual(0, self.setup_hook('--install', '--worktree').returncode)
        self.assertEqual(shared, (self.root / '.git/config').read_bytes())
        other = command[:1] + ['-C', str(self.root / 'linked'), 'config', '--get', 'core.hooksPath']
        self.assertNotEqual(0, subprocess.run(other, capture_output=True).returncode)
