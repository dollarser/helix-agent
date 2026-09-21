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
            self.assertTrue(ci.verify(scope, 'success', 'success', 'success'))
            for result in ['skipped', 'failure', 'cancelled', '']:
                self.assertFalse(ci.verify(scope, 'success', 'success', result))
                self.assertFalse(ci.verify(scope, 'success', result, 'success'))
        self.assertTrue(ci.verify('source', 'success', 'skipped', 'skipped'))
        self.assertFalse(ci.verify('source', 'failure', 'skipped', 'skipped'))
        self.assertFalse(ci.verify('', 'success', 'skipped', 'skipped'))
        self.assertFalse(ci.verify('source', 'success', 'success', 'skipped'))

    def test_source_only_success_is_not_an_android_baseline(self):
        jobs = [{'name': name, 'conclusion': 'success'} for name in
                ['source', 'runtime-assets', 'android (analysis)', 'android (tests-build)', 'verify']]
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
