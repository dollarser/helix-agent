import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('owned_summary', Path(__file__).resolve().parents[1] / 'summarize-owned-runs.py')
summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summary)


def raw(count):
    return ''.join(f'INSTRUMENTATION_STATUS: class=Fixture\nINSTRUMENTATION_STATUS: test=case{i}\n'
                   f'INSTRUMENTATION_STATUS_CODE: {code}\n' for i in range(count) for code in (1, 0)) + 'INSTRUMENTATION_CODE: -1\n'


class OwnedSummaryTest(unittest.TestCase):
    def setUp(self):
        self.area = tempfile.TemporaryDirectory()
        self.addCleanup(self.area.cleanup)
        self.root = Path(self.area.name)

    def run_directory(self, name, count):
        root = self.root / name
        root.mkdir()
        for filename, data in [('artifacts.json', {'app': 'a' * 64, 'test': 'b' * 64}),
                               ('owner.json', {'pid': 100}), ('closed.json', {'pid': 100, 'exit': 0})]:
            (root / filename).write_text(json.dumps(data))
        (root / 'instrumentation.txt').write_text(raw(count))
        return root

    def test_suite_growth_is_counted_from_actual_methods(self):
        result = summary.summarize([self.run_directory('one', 2), self.run_directory('two', 9)])
        self.assertEqual(result['tests'], 11)

    def test_empty_or_failed_or_skipped_results_do_not_pass(self):
        directory = self.run_directory('one', 1)
        for log in ['', 'OK (4 tests)', raw(1).replace('STATUS_CODE: 0', 'STATUS_CODE: -2'),
                    raw(1).replace('STATUS_CODE: 0', 'STATUS_CODE: -4')]:
            with self.subTest(log=log):
                (directory / 'instrumentation.txt').write_text(log)
                with self.assertRaises(ValueError):
                    summary.summarize([directory])

    def test_teardown_owner_and_apk_identity_must_match(self):
        first = self.run_directory('one', 1)
        second = self.run_directory('two', 1)
        (second / 'closed.json').write_text(json.dumps({'pid': 101, 'exit': 0}))
        with self.assertRaises(ValueError):
            summary.summarize([first, second])
        (second / 'closed.json').write_text(json.dumps({'pid': 100, 'exit': 0}))
        (second / 'artifacts.json').write_text(json.dumps({'app': 'c' * 64, 'test': 'b' * 64}))
        with self.assertRaises(ValueError):
            summary.summarize([first, second])
