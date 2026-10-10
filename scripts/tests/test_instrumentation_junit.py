import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from instrumentation_junit import parse, to_xml


def event(code, name='method'):
    return f'INSTRUMENTATION_STATUS: class=Fixture\nINSTRUMENTATION_STATUS: test={name}\nINSTRUMENTATION_STATUS_CODE: {code}\n'


class InstrumentationXmlTest(unittest.TestCase):
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
