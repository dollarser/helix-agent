"""Source import guards for the currently migrated Agent boundaries.

These supplement Gradle compilation; they do not prove runtime/IPC isolation.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


def forbidden_imports(directory, prefixes):
    violations = []
    sources = list((ROOT / directory).rglob('*.kt'))
    if not sources:
        raise AssertionError(f'No sources checked in {directory}')
    for path in sources:
        for line_number, line in enumerate(path.read_text().splitlines(), 1):
            match = re.match(r'\s*import\s+([\w.]+)', line)
            if match and any(match.group(1).startswith(prefix) for prefix in prefixes):
                violations.append(f'{path.relative_to(ROOT)}:{line_number}: {line.strip()}')
    return violations


class AgentLayerBoundariesTest(unittest.TestCase):
    def test_agent_engine_does_not_import_host_or_room(self):
        self.assertEqual([], forbidden_imports(
            'core/agent/src/main/kotlin',
            ('android.', 'androidx.', 'com.helix.app.', 'com.helix.core.storage.'),
        ))

    def test_plugin_content_does_not_import_app_or_orchestration(self):
        self.assertEqual([], forbidden_imports(
            'extensions/plugin/src/main/kotlin',
            ('com.helix.app.', 'com.helix.core.agent.'),
        ))

    def test_migrated_context_mapping_does_not_import_presentation(self):
        self.assertEqual([], forbidden_imports(
            'app/src/main/kotlin/com/helix/app/application/context',
            ('androidx.compose.', 'com.helix.app.chat.', 'com.helix.app.ui.',
             'com.helix.app.infrastructure.'),
        ))
        for name in ('PromptContentContributions', 'PromptEnvironmentSections', 'SystemPromptContext'):
            self.assertFalse((ROOT / f'app/src/main/kotlin/com/helix/app/chat/{name}.kt').exists())

    def test_application_commands_have_no_presentation_imports_or_legacy_aliases(self):
        self.assertEqual([], forbidden_imports(
            'app/src/main/kotlin/com/helix/app/application/agent',
            ('androidx.compose.', 'com.helix.app.chat.', 'com.helix.app.ui.', 'com.helix.app.R'),
        ))
        for name in ('GoalContinuationDriver', 'GoalRoundAllowance', 'GoalRunCoordinator',
                     'TurnInputFingerprint', 'TurnSessionResolver'):
            self.assertFalse((ROOT / f'app/src/main/kotlin/com/helix/app/chat/{name}.kt').exists())
