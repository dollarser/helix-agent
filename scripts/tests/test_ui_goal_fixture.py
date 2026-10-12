"""The UI recovery model must discover tools instead of depending on a warm process."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('ui_goal_fixture', Path(__file__).resolve().parents[1] / 'run-ui-goal-process-kill.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class UiGoalFixtureTest(unittest.TestCase):
    def reply(self, results):
        model = object.__new__(runner.Fixture)
        captured = []
        model.payload = lambda kind, value: captured.append(value)
        model.model({'messages': [{'role': 'user', 'content': 'UI_GOAL_KILL'}] + results})
        chunk = json.loads(captured[0].splitlines()[0].removeprefix('data: '))
        return chunk['choices'][0]['delta']['tool_calls'][0]

    def test_cold_request_discovers_snapshot_before_calling_it(self):
        call = self.reply([])
        self.assertEqual('ui-goal-tools-search-snapshot', call['id'])
        self.assertEqual('tools.search', call['function']['name'])
        self.assertEqual({'query': 'ui.snapshot'}, json.loads(call['function']['arguments']))

    def test_click_discovery_preserves_the_original_snapshot_token(self):
        search = {'role': 'tool', 'tool_call_id': 'ui-goal-tools-search-snapshot', 'content': '[SUCCEEDED] {}'}
        self.assertEqual('ui.snapshot', self.reply([search])['function']['name'])
        snapshot = {'role': 'tool', 'tool_call_id': 'ui-goal-ui-snapshot',
                    'content': '[SUCCEEDED] '+json.dumps({'nodes': [{'text': 'Fixture click', 'token': 'original-token'}]})}
        call = self.reply([search, snapshot])
        self.assertEqual({'query': 'ui.click'}, json.loads(call['function']['arguments']))
        click_search = {'role': 'tool', 'tool_call_id': 'ui-goal-tools-search-click', 'content': '[SUCCEEDED] {}'}
        with tempfile.TemporaryDirectory() as directory:
            runner.Fixture.result_path = Path(directory)/'result.json'
            call = self.reply([search, snapshot, click_search])
        self.assertEqual('ui.click', call['function']['name'])
        self.assertEqual({'token': 'original-token'}, json.loads(call['function']['arguments']))

    def test_denied_snapshot_is_never_treated_as_a_button(self):
        snapshot = {'role': 'tool', 'tool_call_id': 'ui-goal-ui-snapshot', 'content': '[UNKNOWN_TOOL] denied'}
        with self.assertRaises(AssertionError):
            self.reply([snapshot])
