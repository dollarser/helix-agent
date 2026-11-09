"""Flavor Android tests inherit only the already-established Detekt test exemptions."""
import ast
import fnmatch
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_TEST_EXCLUDES = [
    '**/test/**', '**/androidTest/**', '**/commonTest/**', '**/jvmTest/**',
    '**/androidUnitTest/**', '**/androidInstrumentedTest/**', '**/jsTest/**',
    '**/iosTest/**',
]
FLAVOR_TEST_EXCLUDE = '**/src/androidTest*/**'


def rule_exclusions():
    text = (ROOT / 'config/detekt/detekt.yml').read_text()
    # The configuration intentionally uses flat lists; no YAML dependency is
    # necessary for this scoped configuration contract.
    return {
        name: ast.literal_eval(value)
        for name, value in re.findall(r'^  (\w+):\n    excludes: (\[[^\n]+\])$', text, re.M)
    }


class DetektTestSourceContracts(unittest.TestCase):
    def test_existing_test_exemptions_are_preserved_without_new_rule_exemptions(self):
        exclusions = rule_exclusions()
        self.assertEqual(set(exclusions), {'LargeClass', 'TooManyFunctions', 'TooGenericExceptionCaught'})
        self.assertEqual(exclusions['LargeClass'], [
            '**/src/test/**', '**/src/androidTest/**', FLAVOR_TEST_EXCLUDE,
        ])
        for rule in ('TooManyFunctions', 'TooGenericExceptionCaught'):
            self.assertEqual(exclusions[rule], DEFAULT_TEST_EXCLUDES + [FLAVOR_TEST_EXCLUDE])

    def test_shared_flavor_and_variant_android_tests_match(self):
        for rule, patterns in rule_exclusions().items():
            for source in ('androidTest', 'androidTestDeveloper', 'androidTestConsumerDebug'):
                with self.subTest(rule=rule, source=source):
                    path = f'/repo/app/src/{source}/kotlin/com/helix/app/Example.kt'
                    self.assertTrue(any(fnmatch.fnmatchcase(path, pattern) for pattern in patterns))

    def test_production_sources_remain_checked(self):
        for rule, patterns in rule_exclusions().items():
            for source in ('main', 'developer', 'consumer', 'debug', 'developerDebug'):
                with self.subTest(rule=rule, source=source):
                    path = f'/repo/app/src/{source}/kotlin/com/helix/app/Example.kt'
                    self.assertFalse(any(fnmatch.fnmatchcase(path, pattern) for pattern in patterns))

    def test_no_rule_category_or_global_test_exclusion(self):
        text = (ROOT / 'config/detekt/detekt.yml').read_text()
        self.assertNotRegex(text, re.compile(r'^  excludes:', re.M), 'Rule categories must not exclude tests wholesale')
        self.assertIn('  maxIssues: 0', text)
        build = (ROOT / 'build.gradle.kts').read_text()
        self.assertNotIn('**/src/androidTest', build)


if __name__ == '__main__':
    unittest.main()
