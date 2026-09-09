import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('summary', Path(__file__).parents[1] / 'summarize-android-tests.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class AndroidTestSummaryTest(unittest.TestCase):
    def summarize(self, content):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'TEST.xml'
            path.write_text(content)
            return MODULE.summarize([path])

    def test_testsuites_wrapper_does_not_hide_failure_or_skip(self):
        r = self.summarize('<testsuites><testsuite tests="3" failures="1" errors="0" skipped="1">'
                           '<testcase classname="A" name="a"/><testcase classname="A" name="b"><failure/></testcase>'
                           '<testcase classname="A" name="c"><skipped/></testcase></testsuite></testsuites>')
        self.assertEqual((r['passed'], r['failures'], r['skipped'], r['status']), (1, 1, 1, 'FAIL'))

    def test_root_suite_passes(self):
        self.assertEqual(self.summarize('<testsuite><testcase classname="A" name="a"/></testsuite>')['status'], 'PASS')

    def test_empty_is_not_success(self):
        with self.assertRaises(ValueError):
            self.summarize('<testsuites/>')

    def test_inconsistent_counts_rejected(self):
        with self.assertRaises(ValueError):
            self.summarize('<testsuite tests="2"><testcase classname="A" name="a"/></testsuite>')

    def test_duplicate_attempts_rejected(self):
        with self.assertRaises(ValueError):
            self.summarize('<testsuite><testcase classname="A" name="a"/><testcase classname="A" name="a"/></testsuite>')

    def test_skip_is_not_pass(self):
        self.assertEqual(self.summarize('<testsuite><testcase classname="A" name="a"><skipped/></testcase></testsuite>')['status'], 'INCOMPLETE')
