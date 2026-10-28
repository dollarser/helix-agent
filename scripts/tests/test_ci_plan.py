import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('ci_plan', Path(__file__).resolve().parents[1] / 'ci/plan.py')
ci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci)


class CiPlanTest(unittest.TestCase):
    def test_only_known_source_changes_skip_android(self):
        for name in ['docs/a.md', 'README.md', 'README.en.md', 'scripts/README.md',
                     'scripts/tests/test_ci_plan.py', '.agents/skills/example/SKILL.md']:
            self.assertEqual('source', ci.classify([name]), name)
            self.assertEqual('debug', ci.classify([name, 'app/src/main/Main.kt']))
        for name in ['app/build.gradle.kts', 'app/src/release/Main.kt', 'new.file',
                     'app/src/main/AndroidManifest.xml', 'runtime/native.cpp',
                     '.github/workflows/ci.yml', 'scripts/check-all.sh',
                     '.agents/skills/example/agents/openai.yaml', 'scripts/tests/fixture/build.gradle.kts']:
            self.assertEqual('debug', ci.classify([name]), name)
        self.assertEqual('debug', ci.classify([]))

    def test_only_complete_current_android_run_is_baseline(self):
        jobs = [{'name': name, 'conclusion': 'success'} for name in
                ['source', 'runtime-assets', 'android', 'verify']]
        self.assertTrue(ci.successful_android_jobs(jobs))
        for index in range(len(jobs)):
            self.assertFalse(ci.successful_android_jobs(jobs[:index] + jobs[index + 1:]))
            for result in ['failure', 'cancelled', 'skipped', '', None]:
                changed = [dict(job) for job in jobs]
                changed[index]['conclusion'] = result
                self.assertFalse(ci.successful_android_jobs(changed))
        runs = [{'id': 3, 'event': 'workflow_dispatch', 'head_sha': 'c' * 40},
                {'id': 2, 'event': 'push', 'head_sha': 'b' * 40},
                {'id': 1, 'event': 'push', 'head_sha': 'a' * 40}]
        def get(path):
            if '/runs?' in path:
                return {'workflow_runs': runs}
            return {'jobs': jobs if '/1/' in path else [{'name': 'source', 'conclusion': 'success'}]}
        self.assertEqual('a' * 40, ci.proven_base(get, lambda _: True))
        self.assertIsNone(ci.proven_base(get, lambda _: False))

    def test_cancelled_code_then_docs_does_not_hide_code(self):
        with patch.object(ci, 'proven_base', return_value='a' * 40), patch.object(
                ci, 'git', return_value='app/src/main/Main.kt\ndocs/a.md') as git:
            result = ci.plan('push', {'before': 'b' * 40})
        self.assertEqual('debug', result['scope'])
        self.assertEqual('a' * 40, git.call_args.args[-2])
        with patch.object(ci, 'proven_base', return_value='b' * 40), patch.object(
                ci, 'git', return_value='docs/a.md'):
            self.assertEqual('source', ci.plan('push', {})['scope'])

    def test_missing_or_unavailable_baseline_defaults_debug_never_source(self):
        with patch.object(ci, 'proven_base', return_value=None):
            self.assertEqual('debug', ci.plan('push', {})['scope'])
        with patch.object(ci, 'proven_base', side_effect=OSError('unavailable')):
            self.assertEqual('debug', ci.plan('push', {})['scope'])

    def test_pr_complete_diff_and_full_only_by_explicit_dispatch(self):
        with patch.object(ci, 'git', side_effect=['a' * 40, 'docs/a.md']):
            self.assertEqual('source', ci.plan('pull_request', {'pull_request': {'base': {'sha': 'b' * 40}}})['scope'])
        self.assertEqual('full', ci.plan('workflow_dispatch', {})['scope'])
        self.assertEqual('debug', ci.plan('workflow_dispatch', {'inputs': {'scope': 'debug'}})['scope'])
        with self.assertRaises(ValueError):
            ci.plan('workflow_dispatch', {'inputs': {'scope': 'source'}})

    def test_aggregate_rejects_failure_cancellation_unknown_and_unexpected_skip(self):
        for scope in ['debug', 'full']:
            self.assertTrue(ci.verify(scope, 'success', 'success', 'success'))
            for index in range(3):
                for outcome in ['failure', 'cancelled', 'skipped', '', None]:
                    results = ['success'] * 3
                    results[index] = outcome
                    self.assertFalse(ci.verify(scope, *results))
        self.assertTrue(ci.verify('source', 'success', 'skipped', 'skipped'))
        self.assertFalse(ci.verify('', 'success', 'skipped', 'skipped'))
        for results in [('failure', 'skipped', 'skipped'), ('success', 'success', 'skipped'),
                        ('success', 'skipped', 'success'), ('success', 'cancelled', 'skipped')]:
            self.assertFalse(ci.verify('source', *results))
