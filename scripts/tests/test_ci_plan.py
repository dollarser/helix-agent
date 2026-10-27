import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('ci_plan', Path(__file__).resolve().parents[1] / 'ci/plan.py')
ci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci)


class CiPlanTest(unittest.TestCase):
    def test_docs_and_historical_scripts_only(self):
        self.assertEqual('source', ci.classify(['docs/a.md', 'scripts/debug/2026-09-18/example.sh']))
        for path in ['README.md', 'README.en.md', 'CONTRIBUTING.md', 'SECURITY.md', 'THIRD_PARTY_NOTICES.md']:
            with self.subTest(path=path):
                self.assertEqual('source', ci.classify([path]))
                self.assertEqual('full', ci.classify([path, 'app/build.gradle.kts']))
        self.assertEqual('source', ci.classify(['scripts/tests/test_ci_plan.py']))
        self.assertEqual('debug', ci.classify(['scripts/tests/test_ci_plan.py', 'core/model/src/main/Model.kt']))
        self.assertEqual('full', ci.classify(['scripts/tests/fixture/build.gradle.kts']))
        self.assertEqual('full', ci.classify(['scripts/tests/new-language.rs']))

    def test_analysis_split_and_complete_baseline(self):
        self.assertEqual(['analysis', 'release-analysis', 'tests-build'], ci.android_gates('full'))
        self.assertEqual(['analysis', 'tests-build'], ci.android_gates('debug'))
        self.assertTrue(ci.android_gates('source'))
        with self.assertRaises(ValueError):
            ci.android_gates('unknown')
        jobs = [{'name': name, 'conclusion': 'success'} for name in
                ['source', 'runtime-assets', 'speech-assets', 'android (analysis)', 'android (release-analysis)',
                 'android (tests-build)', 'verify']]
        self.assertTrue(ci.successful_android_jobs(jobs))
        debug = [job for job in jobs if job['name'] != 'android (release-analysis)']
        self.assertFalse(ci.successful_android_jobs(debug))
        self.assertTrue(ci.successful_android_jobs(debug, full=False))
        self.assertFalse(ci.successful_android_jobs(
            debug + [{'name': 'android (release-analysis)', 'conclusion': 'failure'}], full=False))
        for index in range(len(jobs)):
            with self.subTest(missing=index):
                self.assertFalse(ci.successful_android_jobs(jobs[:index] + jobs[index + 1:]))
            for result in ['failure', 'cancelled', 'skipped', '']:
                changed = [dict(job) for job in jobs]
                changed[index]['conclusion'] = result
                self.assertFalse(ci.successful_android_jobs(changed))

    def test_shared_code_and_resources_require_debug(self):
        self.assertEqual('debug', ci.classify(['docs/a.md', 'app/src/main/kotlin/Main.kt']))
        self.assertEqual('debug', ci.classify(['app/src/main/res/values/strings.xml']))

    def test_release_configuration_native_and_unknown_require_full(self):
        for path in ['app/build.gradle.kts', 'app/src/main/AndroidManifest.xml', 'gradle/libs.versions.toml',
                     'app/src/release/res/values/strings.xml', 'app/src/consumerRelease/kotlin/Main.kt', 'runtime/src/main/cpp/pty.cpp',
                     'app/gradle.lockfile', 'scripts/check-all.sh', '.github/workflows/ci.yml',
                     '.githooks/pre-commit', 'scripts/secret-pattern.txt', 'new.file']:
            with self.subTest(path=path):
                self.assertEqual('full', ci.classify([path]))
        self.assertEqual('full', ci.classify([]))

    def test_unknown_and_cancelled_gates_never_pass(self):
        for scope in ['debug', 'full']:
            self.assertTrue(ci.verify(scope, 'success', 'success', 'success', 'success'))
            for result in ['skipped', 'failure', 'cancelled', '']:
                self.assertFalse(ci.verify(scope, 'success', 'success', 'success', result))
                self.assertFalse(ci.verify(scope, 'success', result, 'success', 'success'))
                self.assertFalse(ci.verify(scope, 'success', 'success', result, 'success'))
        self.assertTrue(ci.verify('source', 'success', 'skipped', 'skipped', 'skipped'))
        self.assertFalse(ci.verify('source', 'failure', 'skipped', 'skipped', 'skipped'))
        self.assertFalse(ci.verify('', 'success', 'skipped', 'skipped', 'skipped'))
        self.assertFalse(ci.verify('source', 'success', 'success', 'skipped', 'skipped'))
        self.assertFalse(ci.verify('source', 'success', 'skipped', 'success', 'skipped'))

    def test_source_only_success_is_not_an_android_baseline(self):
        jobs = [{'name': name, 'conclusion': 'success'} for name in
                ['source', 'runtime-assets', 'speech-assets', 'android (analysis)',
                 'android (release-analysis)', 'android (tests-build)', 'verify']]
        runs = [{'id': 3, 'event': 'workflow_dispatch', 'head_sha': 'c' * 40},
                {'id': 2, 'event': 'push', 'head_sha': 'b' * 40},
                {'id': 1, 'event': 'push', 'head_sha': 'a' * 40}]
        def get(path):
            if '/runs?' in path:
                return {'workflow_runs': runs}
            return {'jobs': jobs if '/1/' in path else [{'name': 'source', 'conclusion': 'success'}]}
        self.assertEqual('a' * 40, ci.proven_base(get, lambda _: True))
        self.assertIsNone(ci.proven_base(get, lambda _: False))

    def test_cancelled_code_push_then_docs_still_checks_code(self):
        with patch.object(ci, 'proven_base', return_value='a' * 40), patch.object(
                ci, 'git', return_value='app/src/main/Main.kt\ndocs/a.md') as git:
            result = ci.plan('push', {'before': 'b' * 40})
        self.assertEqual('debug', result['scope'])
        self.assertEqual('a' * 40, git.call_args.args[-2])

    def test_debug_success_covers_later_docs_but_never_hides_pending_full_inputs(self):
        with patch.object(ci, 'proven_base', side_effect=['a' * 40, 'b' * 40]), patch.object(
                ci, 'git', side_effect=['core/model/src/main/Model.kt\ndocs/a.md', 'docs/a.md']):
            result = ci.plan('push', {})
        self.assertEqual('source', result['scope'])
        self.assertEqual('b' * 40, result['base'])
        self.assertEqual('a' * 40, result['full_base'])
        with patch.object(ci, 'proven_base', return_value='a' * 40) as baseline, patch.object(
                ci, 'git', return_value='app/build.gradle.kts\ndocs/a.md'):
            result = ci.plan('push', {})
        self.assertEqual('full', result['scope'])
        self.assertEqual(1, baseline.call_count)

    def test_missing_baseline_or_api_error_defaults_full(self):
        with patch.object(ci, 'proven_base', return_value=None):
            self.assertEqual('full', ci.plan('push', {})['scope'])
        with patch.object(ci, 'proven_base', side_effect=OSError('unavailable')):
            self.assertEqual('full', ci.plan('push', {})['scope'])

    def test_pr_uses_complete_diff_and_manual_scope_is_explicit(self):
        with patch.object(ci, 'git', side_effect=['a' * 40, 'docs/a.md']):
            self.assertEqual('source', ci.plan('pull_request', {'pull_request': {'base': {'sha': 'b' * 40}}})['scope'])
        self.assertEqual('full', ci.plan('workflow_dispatch', {})['scope'])
        self.assertEqual('debug', ci.plan('workflow_dispatch', {'inputs': {'scope': 'debug'}})['scope'])
        with self.assertRaises(ValueError):
            ci.plan('workflow_dispatch', {'inputs': {'scope': 'source'}})
