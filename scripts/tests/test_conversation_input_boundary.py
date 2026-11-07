"""Guard the independently compiled composer boundary and its production wiring.

The behavioral proof is :feature:conversation-input:testDebugUnitTest, whose
classpath deliberately contains neither the app nor durable storage.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / 'feature/conversation-input'
PACKAGE = 'com.helix.feature.conversation.input'


class ConversationInputBoundaryTest(unittest.TestCase):
    def test_module_has_only_model_project_dependency(self):
        build = (ROOT / 'build.gradle.kts').read_text()
        dependencies = re.search(
            r'":feature:conversation-input" to listOf\(([^)]*)\)', build)
        self.assertIsNotNone(dependencies)
        self.assertEqual([':core:model'], re.findall(r'"([^"]+)"', dependencies.group(1)))
        self.assertIn('":feature:conversation-input",', (ROOT / 'settings.gradle.kts').read_text())
        self.assertIn('implementation(project(":feature:conversation-input"))',
                      (ROOT / 'app/build.gradle.kts').read_text())

    def test_state_machine_and_tests_have_no_app_or_storage_references(self):
        sources = list((MODULE / 'src').rglob('*.kt'))
        self.assertGreaterEqual(len(sources), 4)
        for path in sources:
            with self.subTest(source=path.relative_to(ROOT)):
                source = path.read_text()
                self.assertIn(f'package {PACKAGE}', source)
                self.assertNotIn('com.helix.app', source)
                self.assertNotIn('com.helix.core.storage', source)

    def test_app_uses_moved_state_machine_and_receipt_contract(self):
        screen = (ROOT / 'app/src/main/kotlin/com/helix/app/ui/ChatScreen.kt').read_text()
        service = (ROOT / 'app/src/main/kotlin/com/helix/app/chat/ChatService.kt').read_text()
        self.assertIn(f'import {PACKAGE}.ConversationDraftBuffer', screen)
        self.assertTrue(f'import {PACKAGE}.ChatSubmissionReceipt' in service,
                        'ChatService must consume the feature receipt contract')
        self.assertFalse((ROOT / 'app/src/main/kotlin/com/helix/app/ui/ConversationDraftBuffer.kt').exists())
        self.assertFalse((ROOT / 'app/src/main/kotlin/com/helix/app/chat/ChatSubmission.kt').exists())

    def test_queue_ui_consumes_only_pending_presentation_values(self):
        for name in ('SessionInputQueuePanel', 'SessionInputQueuePreview', 'SessionInputQueueActions'):
            source = (ROOT / f'app/src/main/kotlin/com/helix/app/ui/{name}.kt').read_text()
            self.assertNotIn('com.helix.core.storage', source, name)
            self.assertIn('PendingInputView', source, name)
        service = (ROOT / 'app/src/main/kotlin/com/helix/app/chat/ChatService.kt').read_text()
        self.assertIn('fun pendingInputViews(', service)
        self.assertIn('mapNotNull(SessionInputRecord::toPendingInputView)', service)
        self.assertNotIn('fun sessionInputDeliveryStatus(', service)

    def test_storage_adaptation_stays_outside_input_module(self):
        mapper = (ROOT / 'app/src/main/kotlin/com/helix/app/chat/ChatSubmissionStorageMapper.kt').read_text()
        self.assertIn('ComposerInputSnapshot', mapper)
        self.assertIn(f'import {PACKAGE}.ChatSubmission', mapper)
        codec = (ROOT / 'core/model/src/main/kotlin/com/helix/core/model/input/KnowledgeReferenceCodec.kt').read_text()
        self.assertNotIn('com.helix.core.storage', codec)
        storage = (ROOT / 'core/storage/src/main/kotlin/com/helix/core/storage/repository/KnowledgeReferences.kt').read_text()
        self.assertNotIn('fun encode(', storage)
        self.assertNotIn('fun decode(', storage)
        self.assertIn('KnowledgeReferenceCodec.encode(', storage)


if __name__ == '__main__':
    unittest.main()
