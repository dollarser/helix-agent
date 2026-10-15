import sys
from pathlib import Path
import unittest
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fixtures.conversation.history import find_session


class ConversationHistoryTest(unittest.TestCase):
    def test_exact_title_can_be_found_after_scrolling(self):
        wrong = {'text': 'target-copy'}
        target = {'text': 'target'}
        nodes = Mock(side_effect=[[wrong], [target]])
        adb = Mock()
        pause = Mock()
        self.assertIs(target, find_session('target', 'history', nodes=nodes, adb=adb, pause=pause))
        self.assertEqual(2, nodes.call_count)
        adb.assert_called_once_with('shell', 'input', 'swipe', '500', '1700', '500', '550', '300')
        pause.assert_called_once_with(.3)

    def test_missing_title_is_bounded_and_not_silently_accepted(self):
        nodes = Mock(return_value=[{'text': 'target-copy'}])
        with self.assertRaisesRegex(RuntimeError, 'Seeded session not found'):
            find_session('target', 'history', nodes=nodes, adb=Mock(), pause=Mock())
        self.assertEqual(30, nodes.call_count)

    def test_failed_observation_does_not_trigger_swipe(self):
        adb = Mock()
        with self.assertRaises(ValueError):
            find_session('target', 'history', nodes=Mock(side_effect=ValueError('failed observation')),
                         adb=adb, pause=Mock())
        adb.assert_not_called()
