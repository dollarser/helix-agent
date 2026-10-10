import sys
import subprocess
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from instrumentation_junit import parse, require_passed, to_xml


def event(code, name='method'):
    return f'INSTRUMENTATION_STATUS: class=Fixture\nINSTRUMENTATION_STATUS: test={name}\nINSTRUMENTATION_STATUS_CODE: {code}\n'


class InstrumentationXmlTest(unittest.TestCase):
    def test_cli_accepts_raw_results_and_rejects_summary_only(self):
        script = Path(__file__).resolve().parents[1] / 'instrumentation_junit.py'
        for raw, code in [(event(1) + event(0) + 'INSTRUMENTATION_CODE: -1\n', 0), ('OK (1 test)', 1)]:
            result = subprocess.run([sys.executable, str(script)], input=raw, text=True,
                                    capture_output=True, timeout=10)
            self.assertEqual(result.returncode, code)

    def test_pass_validation_tracks_growing_suites_without_fixed_totals(self):
        for count in (1, 8, 11):
            with self.subTest(count=count):
                raw = ''.join(event(1, str(i)) + event(0, str(i)) for i in range(count))
                self.assertEqual(len(require_passed(raw + 'INSTRUMENTATION_CODE: -1\n')), count)

    def test_pass_validation_rejects_skips_failures_and_dynamic_total_mismatch(self):
        for code in (-1, -2, -3, -4):
            with self.subTest(code=code), self.assertRaises(ValueError):
                require_passed(event(1) + event(code) + 'INSTRUMENTATION_CODE: -1\n')
        with self.assertRaisesRegex(ValueError, 'total differs'):
            start = event(1).replace('INSTRUMENTATION_STATUS_CODE: 1',
                                     'INSTRUMENTATION_STATUS: numtests=2\nINSTRUMENTATION_STATUS_CODE: 1')
            require_passed(start + event(0) + 'INSTRUMENTATION_CODE: -1\n')

    def test_stream_diagnostic_is_not_an_extra_test_or_terminal(self):
        diagnostic = 'INSTRUMENTATION_STATUS: stream=IME preedit\nINSTRUMENTATION_STATUS_CODE: 0\n'
        records = parse(event(1) + diagnostic + event(0) + 'INSTRUMENTATION_CODE: -1\n')
        self.assertEqual({('Fixture', 'method'): (0, '')}, records)
        for raw in [diagnostic + 'INSTRUMENTATION_CODE: -1\n',
                    event(1) + diagnostic + 'INSTRUMENTATION_CODE: -1\n']:
            with self.assertRaises(ValueError):
                parse(raw)

    def test_missing_or_partial_identity_cannot_hide_as_diagnostic(self):
        for bundle in ['stream=detail\nINSTRUMENTATION_STATUS: class=Fixture',
                       'stream=detail\nINSTRUMENTATION_STATUS: test=method',
                       'stream=detail\nINSTRUMENTATION_STATUS: numtests=1']:
            raw = event(1) + 'INSTRUMENTATION_STATUS: ' + bundle + '\nINSTRUMENTATION_STATUS_CODE: 0\n'
            with self.assertRaises(ValueError):
                parse(raw + event(0) + 'INSTRUMENTATION_CODE: -1\n')

    def test_pass_requires_start_and_terminal(self):
        records = parse(event(1) + event(0) + 'INSTRUMENTATION_CODE: -1\n')
        self.assertIn('classname="Fixture"', to_xml(records))
        self.assertNotIn('time=', to_xml(records))

    def test_error_failure_and_skips_preserved(self):
        for code, tag in [(-1, 'error'), (-2, 'failure'), (-3, 'skipped'), (-4, 'skipped')]:
            self.assertIn('<' + tag, to_xml(parse(event(1) + event(code) + 'INSTRUMENTATION_CODE: -1\n')))

    def test_truncated_duplicate_and_summary_only_rejected(self):
        for raw in ['OK (1 test)', event(1), event(0), event(1) + event(0) + event(1),
                    event(1) + event(0) + event(0), event(1) + event(0) + 'INSTRUMENTATION_CODE: 0\n']:
            with self.assertRaises(ValueError):
                parse(raw)


if __name__ == '__main__':
    unittest.main()
